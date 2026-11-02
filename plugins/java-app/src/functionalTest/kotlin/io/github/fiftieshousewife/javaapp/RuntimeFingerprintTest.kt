package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The runtime's fingerprint, published beside it, and the guard comparing a release with the one before. */
class RuntimeFingerprintTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()
    private val published = "repo/com/example/test/svc-runtime"

    private fun released(): TestProject = javaService(
        tmp,
        JavaServiceOptions(build = """publishing { repositories { maven { name = "local"; url = uri("repo") } } }"""),
    ).apply {
        project.resolve("settings.gradle.kts").appendText(
            "\ndependencyResolutionManagement { repositories { exclusiveContent { " +
                "forRepository { maven { url = uri(\"repo\") } }; " +
                "filter { includeGroup(\"com.example.test\") } } } }\n",
        )
        build("publish")
    }

    private fun fingerprint(project: TestProject, version: String) =
        project.project.resolve("$published/$version/svc-runtime-$version-$host-inputs.txt")

    @Test
    fun `is published beside the runtime archive and names the runtime's inputs, not the app`() {
        val lines = fingerprint(released(), "2.0.0").readLines()
        assertAll(
            { assertTrue(lines.any { it.startsWith("modules=") && "jdk.httpserver" in it }, lines.joinToString("\n")) },
            {
                assertTrue(
                    lines.any {
                        it.startsWith("file ") && it.contains("java.base.jmod")
                    },
                    lines.joinToString("\n"),
                )
            },
            { assertTrue(lines.any { it.startsWith("file java-app-customisations.txt ") }, lines.joinToString("\n")) },
            { assertTrue(lines.none { "svc" in it || "2.0.0" in it }, lines.joinToString("\n")) },
        )
    }

    @Test
    fun `a new release on the same inputs stores nothing new`() {
        val project = released()
        val result = project.build(
            "appVerifyRuntimeFingerprint",
            "-PappVersion=2.0.1",
            "-PjavaApp.previousRelease=latest.release",
        )
        assertAll(
            { assertTrue(result.output.contains("$host: the same runtime as 2.0.0, stored once"), result.output) },
            {
                val local = project.project.resolve("build/distributions/svc-runtime-2.0.1-$host-inputs.txt")
                assertEquals(fingerprint(project, "2.0.0").readText(), local.readText())
            },
        )
    }

    @Test
    fun `publishing fails when the inputs are the same but the archive is not`() {
        val project = released()
        val archive = project.project.resolve("$published/2.0.0/svc-runtime-2.0.0-$host.tar.gz")
        archive.appendBytes(byteArrayOf(0))
        val refused = project.buildAndFail("publish", "-PappVersion=2.0.1", "-PjavaApp.previousRelease=2.0.0")
        assertTrue(refused.output.contains("so the repository would store a second copy"), refused.output)
    }
}
