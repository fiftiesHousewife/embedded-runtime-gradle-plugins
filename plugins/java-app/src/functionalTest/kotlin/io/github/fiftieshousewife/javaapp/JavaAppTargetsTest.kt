package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.checks
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Several targets in one build, and consuming builds. */
class JavaAppTargetsTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()
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
    fun `a build for another machine only links with this machine's JDK`() {
        assumeTrue(host != "linux-aarch64", "the fixture publishes linux-aarch64 as the other machine")
        val project = javaService(tmp, JavaServiceOptions(javaApp = "$FIXTURE_MODULES\ntargets(\"linux-aarch64\")"))
        project.build("appArchive")
        assertTrue(project.project.resolve("build/distributions/svc-2.0.0-linux-aarch64.tar.gz").isFile)
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
}
