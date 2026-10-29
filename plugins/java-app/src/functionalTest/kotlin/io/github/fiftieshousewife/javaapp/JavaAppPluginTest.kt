package io.github.fiftieshousewife.javaapp

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
import java.nio.file.attribute.PosixFilePermissions
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
    fun `plain installDist installs this machine's app with its embedded runtime`() {
        val project = javaService(tmp)
        project.build("installDist")
        val installed = project.project.resolve("build/install/svc")
        val version = TestProject.run(installed.resolve("bin/svc").absolutePath, "version")
        assertAll(
            { assertTrue(installed.resolve("runtime/bin/java").canExecute()) },
            { assertTrue(installed.resolve("META-INF/sbom.cdx.json").isFile) },
            {
                assertTrue(
                    version.contains("\"version\": \"2.0.0\"") || version.contains("\"version\":\"2.0.0\""),
                    version,
                )
            },
        )
    }

    @Test
    fun `out of memory, the app exits and leaves a private heap dump in the log directory`() {
        val project = javaService(tmp)
        project.file("src/main/java/test/Main.java", OUT_OF_MEMORY_APP)
        project.build("installDist")
        val installed = project.project.resolve("build/install/svc")
        val (exit, output) = runWithSmallHeap(installed.resolve("bin/svc"))
        val logs = installed.resolve("logs")
        val dumps = logs.listFiles { f -> f.name.endsWith(".hprof") }.orEmpty()
        fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
        assertAll(
            { assertEquals(OUT_OF_MEMORY_EXIT, exit, output) },
            { assertEquals(1, dumps.size, output) },
            { assertEquals("rwxr-x---", mode(logs)) },
            { assertEquals("rw-------", mode(dumps.single())) },
        )
    }

    @Test
    fun `an account that can't create the log directory still starts the app`() {
        val project = javaService(tmp)
        project.file("src/main/java/test/Main.java", OUT_OF_MEMORY_APP)
        project.build("installDist")
        val installed = project.project.resolve("build/install/svc")
        installed.setWritable(false) // as /opt/<app> is to the service account
        val (exit, output) = try {
            runWithSmallHeap(installed.resolve("bin/svc"))
        } finally {
            installed.setWritable(true)
        }
        // The app ran (and ran out of memory) rather than the launcher stopping at mkdir.
        assertEquals(OUT_OF_MEMORY_EXIT, exit, output)
    }

    private fun runWithSmallHeap(launcher: File): Pair<Int, String> {
        val process = ProcessBuilder(launcher.absolutePath, "run")
            .redirectErrorStream(true)
            .apply { environment()["APP_JAVA_OPTS"] = "-Xmx16m" }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
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
    fun `a dependency only its owner may read is readable by everyone in the archive`() {
        val dependency = "dependencies { implementation(files(\"private.jar\")) }"
        val project = javaService(tmp, JavaServiceOptions(build = dependency))
        val jar = project.project.resolve("private.jar").toPath()
        Files.write(jar, EMPTY_ZIP)
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-------"))
        project.build("appArchive")
        val entries = TestProject.run("tar", "-tvzf", archive(project).absolutePath)
        assertTrue(entries.lines().any { it.startsWith("-rw-r--r--") && it.endsWith("lib/private.jar") }, entries)
    }

    private fun sha256(file: File) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())))

    private companion object {
        /** What HotSpot exits with under -XX:+ExitOnOutOfMemoryError. */
        const val OUT_OF_MEMORY_EXIT = 3

        /** A zip with no entries: its end-of-central-directory record alone. */
        val EMPTY_ZIP = byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18)
        const val OUT_OF_MEMORY_APP = """
            package test;
            public class Main {
                public static void main(String[] args) {
                    java.util.List<byte[]> held = new java.util.ArrayList<>();
                    while (true) held.add(new byte[1 << 20]);
                }
            }
            """
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
