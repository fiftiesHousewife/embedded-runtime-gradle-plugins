package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.checks
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

class JavaAppPluginTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()

    private fun archive(project: TestProject, version: String = "2.0.0") =
        project.project.resolve("build/distributions/svc-$version-$host.tar.gz")

    @Test
    fun `builds and smoke-tests a self-contained archive laid out like the application plugin's`() {
        val project = javaService(tmp)
        val result = project.build("build")
        val entries = TestProject.run("tar", "-tvzf", archive(project).absolutePath).lines()
        val smokeTested = { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) }
        val present = LAYOUT.map { path ->
            { assertTrue(entries.any { it.endsWith("svc-2.0.0/$path") }, "missing $path") }
        }
        val executable =
            EXECUTABLES.map { path ->
                { assertTrue(entries.any { it.startsWith("-rwxr-xr-x") && it.endsWith(path) }, "$path") }
            }
        assertAll(checks(listOf(smokeTested) + present + executable))
    }

    @Test
    fun `rebuilding the same sources produces a byte-identical archive from the configuration cache`() {
        val project = javaService(tmp)
        project.build("clean", "appTar")
        val first = sha256(archive(project))
        val rebuilt = project.build("clean", "appTar")
        assertAll(
            { assertTrue(rebuilt.output.contains("Reusing configuration cache"), rebuilt.output) },
            { assertEquals(first, sha256(archive(project))) },
        )
    }

    @Test
    fun `the archive installs, keeps an operator's configuration through an upgrade, and rolls back`() {
        val project = javaService(tmp)
        project.build("appTar")
        project.build("appTar", "-PappVersion=2.1.0")
        val installer = Installer(tmp.resolve("srv/svc"))
        try {
            installer.install(archive(project))
            val seeded = installer.config("app.properties").readText()
            installer.config("app.properties").writeText("key=edited\n")
            installer.install(archive(project, "2.1.0"))
            val upgraded = installer.current
            val status = installer.status()
            installer.rollback()
            assertAll(
                { assertEquals("key=value\n", seeded) },
                { assertEquals("2.1.0", upgraded) },
                { assertEquals("key=edited\n", installer.config("app.properties").readText()) },
                { assertTrue(status.contains("health:   healthy"), status) },
                { assertEquals("2.0.0", installer.current) },
            )
        } finally {
            installer.stop()
        }
    }

    private fun sha256(file: File) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())))

    private companion object {
        val LAYOUT =
            listOf(
                "bin/svc",
                "runtime/bin/java",
                "lib/svc-2.0.0.jar",
                "conf.defaults/app.properties",
                "META-INF/app.json",
                "META-INF/sbom.cdx.json",
            )
        val EXECUTABLES = listOf("svc-2.0.0/bin/svc", "runtime/lib/jspawnhelper")
    }
}
