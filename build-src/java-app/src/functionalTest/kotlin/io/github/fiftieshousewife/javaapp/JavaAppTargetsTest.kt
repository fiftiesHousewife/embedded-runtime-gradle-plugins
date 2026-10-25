package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Several targets in one build, where JDKs come from, and the container context. */
class JavaAppTargetsTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()
    private val localRepo = """publishing { repositories { maven { name = "local"; url = uri(layout.buildDirectory.dir("repo")) } } }"""

    @Test
    fun `one build archives and publishes several targets, smoke-testing this machine's`() {
        val project = javaService(tmp, javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\")", build = localRepo)

        val built = project.build("build")
        assertEquals(TaskOutcome.SUCCESS, built.task(":appSmokeTest")?.outcome)
        listOf(host, "linux-aarch64").forEach {
            assertTrue(project.project.resolve("build/distributions/svc-2.0.0-$it.tar.gz").isFile, "no $it archive")
        }

        val refused = project.buildAndFail("publish")
        assertTrue(refused.output.contains("Refusing to publish: the linux-aarch64 archive was never started"), refused.output)

        project.build("publish", "-PjavaApp.allowUntestedPublish=true")
        val published = project.project.resolve("build/repo/com/example/test/svc/2.0.0")
        listOf(host, "linux-aarch64").forEach {
            assertTrue(published.resolve("svc-2.0.0-$it.tar.gz").isFile, "$it archive not published")
            assertTrue(published.resolve("svc-2.0.0-$it-cyclonedx.json").isFile, "$it SBOM not published")
        }

        // One Gradle Module Metadata file describes every target with Gradle's own attributes, so a
        // consuming Gradle build can pick the archive for its machine without naming a classifier.
        val gmm = published.resolve("svc-2.0.0.module").readText()
        listOf(
            """"org.gradle.native.operatingSystem": "linux"""",
            """"org.gradle.native.architecture": "aarch64"""",
            """"org.gradle.dependency.bundling": "embedded"""",
            "svc-2.0.0-linux-aarch64.tar.gz",
            "svc-2.0.0-$host.tar.gz",
        ).forEach { assertTrue(gmm.contains(it), "missing $it in\n$gmm") }
    }

    @Test
    fun `the container context holds the project's own Dockerfile and the Linux archive`() {
        val project = javaService(tmp, javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\")")
        project.file("Dockerfile", "# the project's own\nFROM scratch")

        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context")
        assertEquals("# the project's own\nFROM scratch\n", context.resolve("Dockerfile").readText())
        assertTrue(context.resolve("app/bin/svc").isFile, "the app must sit under app/ in $context")
        val manifest = context.resolve("app/META-INF/app.json").readText()
        assertTrue(manifest.contains(""""target": "linux-aarch64""""), "containers need the Linux archive:\n$manifest")
    }

    @Test
    fun `a JDK installed in a directory is used as it is`() {
        // Nothing on the download site: the JDK comes from its directory, and Gradle compiles with
        // it because it is listed as an installation, as the README tells users to do.
        val project =
            javaService(
                tmp,
                publishJdk = false,
                properties =
                    arrayOf(
                        JdkSources.pathProperty(
                            JdkFixture.javaFeature,
                            io.github.fiftieshousewife.appcore.Target
                                .parse(host),
                        ) to
                            JdkFixture.home.absolutePath,
                        "org.gradle.java.installations.paths" to JdkFixture.home.absolutePath,
                    ),
            )

        val result = project.build("build")
        assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome)
        assertEquals(null, result.task(":extractJdk${io.github.fiftieshousewife.appcore.Target.parse(host).taskSuffix}"))
    }

    @Test
    fun `JDK archives are read from a shared local directory`() {
        val shared = tmp.resolve("shared-jdks")
        val project =
            javaService(
                tmp,
                publishJdk = false,
                properties = arrayOf(JdkSources.LOCAL_PATH to shared.absolutePath),
            )
        JdkFixture.publishCurated(project, shared)

        val result = project.build("build")
        assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome)
    }

    @Test
    fun `a consuming build smoke-tests the published archive for its own machine`() {
        val producer =
            javaService(tmp.resolve("producer"), javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\")", build = localRepo)
        producer.build("publish", "-PjavaApp.allowUntestedPublish=true")
        val repo = producer.project.resolve("build/repo")

        // A plain build with no app of its own: only a repository and the dependency to test.
        val consumer =
            TestProject(tmp.resolve("consumer")).apply {
                gradleProperties()
                file("settings.gradle.kts", """rootProject.name = "deploy-check"""")
                file(
                    "build.gradle.kts",
                    """
                    plugins { id("io.github.fiftieshousewife.app-smoke-test") }
                    repositories { maven { url = uri("${repo.toURI()}") } }
                    dependencies { smokeTest("com.example.test:svc:2.0.0") }
                    """,
                )
            }

        val result = consumer.build("appSmokeTest")
        assertTrue(result.output.contains("Smoke test passed: svc-2.0.0-$host.tar.gz"), result.output)
    }
}
