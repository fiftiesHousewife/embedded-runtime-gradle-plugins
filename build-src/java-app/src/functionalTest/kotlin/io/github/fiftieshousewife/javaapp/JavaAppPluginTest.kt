package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.InstallLayout
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.checks
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            { assertTrue(entries.any { it.endsWith("svc-2.0.0-${TestProject.hostTarget()}/$path") }, "missing $path") }
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
        project.build("clean", "appArchive")
        val first = sha256(archive(project))
        val rebuilt = project.build("clean", "appArchive")
        assertAll(
            { assertTrue(rebuilt.output.contains("Reusing configuration cache"), rebuilt.output) },
            { assertEquals(first, sha256(archive(project))) },
        )
    }

    @Test
    fun `the launchers come from Gradle's own startScripts, and a build's own generator still wins`() {
        val own =
            """
            tasks.startScripts {
                unixStartScriptGenerator = org.gradle.jvm.application.scripts.ScriptGenerator { _, out ->
                    out.write("#!/bin/sh\necho custom\n")
                }
            }
            """.trimIndent()
        val project = javaService(tmp, JavaServiceOptions(build = own))
        val result = project.build("appArchive")
        val launcher = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/bin/svc").readText()
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":startScripts")?.outcome) },
            { assertEquals("#!/bin/sh\necho custom\n", launcher) },
        )
    }

    @Test
    fun `a JDK with read-only files is extracted again and keeps its exec bits`() {
        val project = javaService(tmp)
        project.build("appArchive")
        val suffix = Target.parse(TestProject.hostTarget()).taskSuffix
        val jdk = project.project.resolve("build/app-work/jdk/${TestProject.hostTarget()}")
        val readOnly = jdk.walkTopDown().first { it.isFile && it.name == "release" }.apply { setWritable(false) }
        val again = project.build("extractJdk$suffix", "--rerun", "customiseRuntime$suffix", "--rerun")
        val java = project.project.resolve("build/app-work/runtime/${TestProject.hostTarget()}/bin/java")
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, again.task(":extractJdk$suffix")?.outcome) },
            { assertTrue(readOnly.canWrite()) },
            { assertTrue(java.canExecute(), "$java lost its exec bit") },
        )
    }

    @Test
    fun `appInstall switches to a release only after it starts healthy, keeps configuration, and rolls back`() {
        val project = javaService(tmp)
        val srv = tmp.resolve("srv")
        val dir = "-PjavaApp.installDir=${srv.absolutePath}"
        project.build("appInstall", dir)
        srv.resolve("conf/app.properties").writeText("key=edited\n")
        project.build("appInstall", dir, "-PappVersion=2.1.0")
        val upgraded = InstallLayout(srv).current?.name to InstallLayout(srv).previous?.name
        srv.resolve("conf/jvm.options").writeText("-XX:+NoSuchOptionAnywhere\n")
        val broken = project.buildAndFail("appInstall", dir, "-PappVersion=2.2.0")
        srv.resolve("conf/jvm.options").delete()
        project.build("appRollback", dir)
        assertAll(
            { assertEquals("svc-2.1.0" to "svc-2.0.0", upgraded) },
            { assertEquals("key=edited\n", srv.resolve("conf/app.properties").readText()) },
            { assertTrue(broken.output.contains("it was not installed and current is unchanged"), broken.output) },
            { assertFalse(srv.resolve("releases/svc-2.2.0").exists()) },
            { assertEquals("svc-2.0.0", InstallLayout(srv).current?.name) },
        )
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
        val EXECUTABLES = listOf("bin/svc", "runtime/lib/jspawnhelper")
    }
}
