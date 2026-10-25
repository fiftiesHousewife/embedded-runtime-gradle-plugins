package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.checks
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Several targets in one build, where JDKs come from, the container context and consuming builds. */
class JavaAppTargetsTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()
    private val linuxForThisCpu = "linux-" + host.substringAfter('-')
    private val localRepo =
        """publishing { repositories { maven { name = "local"; url = uri(layout.buildDirectory.dir("repo")) } } }"""
    private val twoTargets =
        JavaServiceOptions(javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\")", build = localRepo)

    @Test
    fun `one build archives every target and smoke-tests this machine's`() {
        val project = javaService(tmp, twoTargets)
        val built = project.build("build")
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, built.task(":appSmokeTest")?.outcome) },
            { assertTrue(project.project.resolve("build/distributions/svc-2.0.0-$host.tar.gz").isFile) },
            { assertTrue(project.project.resolve("build/distributions/svc-2.0.0-linux-aarch64.tar.gz").isFile) },
        )
    }

    @Test
    fun `publishing refuses archives never started here unless allowed`() {
        val project = javaService(tmp, twoTargets)
        val refused = project.buildAndFail("publish")
        assertTrue(
            refused.output.contains("Refusing to publish: the linux-aarch64 archive was never started"),
            refused.output,
        )
    }

    @Test
    fun `every target publishes under one version, described by one module metadata file`() {
        val project = javaService(tmp, twoTargets)
        project.build("publish", "-PjavaApp.allowUntestedPublish=true")
        val published = project.project.resolve("build/repo/com/example/test/svc/2.0.0")
        val gmm = published.resolve("svc-2.0.0.module").readText()
        val files = listOf(host, "linux-aarch64").flatMap {
            listOf("svc-2.0.0-$it.tar.gz", "svc-2.0.0-$it-cyclonedx.json")
        }
        val attributes =
            listOf(
                """"org.gradle.native.operatingSystem": "linux"""",
                """"org.gradle.native.architecture": "aarch64"""",
                """"org.gradle.dependency.bundling": "embedded"""",
            )
        assertAll(
            checks(
                files.map { file -> { assertTrue(published.resolve(file).isFile, "$file not published") } } +
                    attributes.map { attribute ->
                        { assertTrue(gmm.contains(attribute), "missing $attribute in\n$gmm") }
                    },
            ),
        )
    }

    @Test
    fun `the container context holds the project's own Dockerfile and the Linux archive for this CPU`() {
        val project =
            javaService(
                tmp,
                JavaServiceOptions(
                    javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\", \"$linuxForThisCpu\")",
                ),
            )
        JdkFixture.publish(project, linuxForThisCpu)
        project.file("Dockerfile", "# the project's own\nFROM scratch")
        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context")
        assertAll(
            { assertEquals("# the project's own\nFROM scratch\n", context.resolve("Dockerfile").readText()) },
            { assertTrue(context.resolve("app/bin/svc").isFile) },
            {
                assertTrue(
                    context.resolve("app/META-INF/app.json").readText().contains(""""target": "$linuxForThisCpu""""),
                )
            },
        )
    }

    @Test
    fun `a JDK installed in a directory is used as it is`() {
        val jdk = JdkFixture.home.absolutePath
        val path = JdkSources.pathProperty(JdkFixture.javaFeature, Target.parse(host)) to jdk
        val project = javaService(
            tmp,
            JavaServiceOptions(
                publishJdk = false,
                properties = listOf(
                    path,
                    "org.gradle.java.installations.paths" to jdk,
                ),
            ),
        )
        val result = project.build("build")
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
            { assertNull(result.task(":extractJdk${Target.parse(host).taskSuffix}")) },
        )
    }

    @Test
    fun `JDK archives are read from a shared local directory`() {
        val shared = tmp.resolve("shared-jdks")
        val project =
            javaService(
                tmp,
                JavaServiceOptions(
                    publishJdk = false,
                    properties = listOf(JdkSources.LOCAL_PATH to shared.absolutePath),
                ),
            )
        JdkFixture.publishCurated(project, shared)
        assertEquals(TaskOutcome.SUCCESS, project.build("build").task(":appSmokeTest")?.outcome)
    }

    @Test
    fun `a consuming build smoke-tests the published archive for its own machine`() {
        val producer = javaService(tmp.resolve("producer"), twoTargets)
        producer.build("publish", "-PjavaApp.allowUntestedPublish=true")
        val consumer =
            TestProject(tmp.resolve("consumer")).apply {
                gradleProperties()
                file("settings.gradle.kts", """rootProject.name = "deploy-check"""")
                file(
                    "build.gradle.kts",
                    fixture(
                        "consumer/build.gradle.kts.template",
                    ).replace("@REPO@", producer.project.resolve("build/repo").toURI().toString()),
                )
            }
        val result = consumer.build("appSmokeTest")
        assertTrue(result.output.contains("Smoke test passed: svc-2.0.0-$host.tar.gz"), result.output)
    }

    @Test
    fun `a deployment build installs the published archive for its own machine`() {
        val producer = javaService(tmp.resolve("producer"), twoTargets)
        producer.build("publish", "-PjavaApp.allowUntestedPublish=true")
        val srv = tmp.resolve("srv")
        val deploy =
            TestProject(tmp.resolve("deploy")).apply {
                gradleProperties()
                file("settings.gradle.kts", """rootProject.name = "deploy"""")
                file(
                    "build.gradle.kts",
                    fixture("deploy/build.gradle.kts.template")
                        .replace("@REPO@", producer.project.resolve("build/repo").toURI().toString())
                        .replace("@DIR@", srv.invariantSeparatorsPath),
                )
            }
        val result = deploy.build("appInstall")
        assertAll(
            { assertTrue(result.output.contains("svc-2.0.0 started against"), result.output) },
            { assertEquals("svc-2.0.0", io.github.fiftieshousewife.appcore.InstallLayout(srv).current?.name) },
        )
    }
}
