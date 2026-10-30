package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.checks
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

/** The runtime archive: `runtime/` alone, the same bytes for every build and version while its inputs are unchanged. */
class RuntimeArchiveTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()

    private fun runtimeArchive(project: TestProject, version: String = "2.0.0") =
        project.project.resolve("build/distributions/svc-runtime-$version-$host.tar.gz")

    @Test
    fun `holds the runtime alone, at its root, with fixed modes and its executables`() {
        val project = javaService(tmp)
        project.build("appArchive")
        val entries = TestProject.run("tar", "-tvzf", runtimeArchive(project).absolutePath).lines()
            .filter(String::isNotBlank)
        val paths = entries.map { it.substringAfterLast(' ') }
        val modes = entries.map { it.substringBefore(' ') }.toSet()
        val executable = EXECUTABLES.map { path ->
            { assertTrue(entries.any { it.startsWith("-rwxr-xr-x") && it.endsWith(" $path") }, path) }
        }
        val layout = listOf(
            { assertTrue(paths.all { it.startsWith("runtime/") }, paths.joinToString("\n")) },
            { assertTrue("runtime/release" in paths, paths.joinToString("\n")) },
            { assertEquals(setOf("drwxr-xr-x", "-rw-r--r--", "-rwxr-xr-x"), modes) },
        )
        assertAll(checks(layout + executable))
    }

    @Test
    fun `a rebuild and a new version of the app give a byte-identical runtime archive`() {
        val project = javaService(tmp)
        project.build("clean", "appArchive")
        val first = sha256(runtimeArchive(project))
        project.build("clean", "appArchive", "--rerun-tasks")
        val rebuilt = sha256(runtimeArchive(project))
        project.build("appArchive", "-PappVersion=2.0.1")
        assertAll(
            { assertEquals(first, rebuilt) },
            { assertEquals(first, sha256(runtimeArchive(project, "2.0.1"))) },
        )
    }

    @Test
    fun `the smoke test starts the app archive on its runtime archive`() {
        val project = javaService(tmp)
        val result = project.build("appSmokeTest")
        assertTrue(result.output.contains("Smoke test passed: svc-2.0.0-$host.tar.gz"), result.output)
    }

    private fun sha256(file: File) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())))

    private companion object {
        val EXECUTABLES = listOf("runtime/bin/java", "runtime/lib/jspawnhelper")
    }
}
