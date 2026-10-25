package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest

class JavaAppPluginTest {
    @TempDir
    lateinit var tmp: File

    private fun javaService(extraBuild: String = "") = javaService(tmp, build = extraBuild)

    @Test
    fun `builds, smoke-tests and reproduces a self-contained archive`() {
        val project = javaService()
        val first = project.build("build")
        assertEquals(TaskOutcome.SUCCESS, first.task(":appSmokeTest")?.outcome)

        val archive = project.project.resolve("build/distributions/svc-2.0.0-${TestProject.hostTarget()}.tar.gz")
        val entries = TestProject.run("tar", "-tvzf", archive.absolutePath)
        listOf(
            "svc-2.0.0/bin/svc",
            "svc-2.0.0/runtime/bin/java",
            "svc-2.0.0/lib/svc-2.0.0.jar",
            "svc-2.0.0/conf.defaults/app.properties",
            "svc-2.0.0/META-INF/app.json",
            "svc-2.0.0/META-INF/sbom.cdx.json",
        ).forEach { assertTrue(entries.contains(it), "missing $it in\n$entries") }
        assertTrue(entries.lines().any { it.startsWith("-rwxr-xr-x") && it.endsWith("svc-2.0.0/bin/svc") }, entries)
        assertTrue(entries.lines().any { it.startsWith("-rwxr-xr-x") && it.endsWith("runtime/lib/jspawnhelper") }, entries)

        project.build("clean", "appTar")
        val checksum = sha256(archive)
        val rebuilt = project.build("clean", "appTar")
        assertTrue(rebuilt.output.contains("Reusing configuration cache"), rebuilt.output)
        assertEquals(checksum, sha256(archive), "rebuilding the same sources must produce an identical archive")
    }

    @Test
    fun `the archive installs, upgrades and rolls back with deploy-install-sh`() {
        val project = javaService()
        project.build("appTar")
        val first = project.project.resolve("build/distributions/svc-2.0.0-${TestProject.hostTarget()}.tar.gz")
        project.build("appTar", "-PappVersion=2.1.0")
        val second = project.project.resolve("build/distributions/svc-2.1.0-${TestProject.hostTarget()}.tar.gz")

        // conf/env is the operator's per-server file; a free port keeps parallel test runs apart.
        val root = tmp.resolve("srv/svc")
        val port = ServerSocket(0).use { it.localPort }
        root.resolve("conf").mkdirs()
        root.resolve("conf/env").writeText("APP_PORT=$port\n")

        fun install(vararg args: String) =
            TestProject.run("bash", System.getProperty("installScript"), *args, "--app-root", root.absolutePath)

        try {
            install("install", "--archive", first.absolutePath, "--supervisor", "nohup", "--health-timeout", "60")
            assertEquals("2.0.0", root.resolve("current").canonicalFile.name)
            assertEquals("key=value\n", root.resolve("conf/app.properties").readText(), "conf/ is seeded from conf.defaults/")

            // An operator edit must survive an upgrade: conf/ is seeded once, never overwritten.
            root.resolve("conf/app.properties").writeText("key=edited\n")
            install("install", "--archive", second.absolutePath, "--supervisor", "nohup", "--health-timeout", "60")
            assertEquals("2.1.0", root.resolve("current").canonicalFile.name)
            assertEquals("key=edited\n", root.resolve("conf/app.properties").readText())
            val status = install("status", "--supervisor", "nohup")
            assertTrue(status.contains("health:   healthy"), status)

            install("rollback", "--supervisor", "nohup", "--health-timeout", "60")
            assertEquals("2.0.0", root.resolve("current").canonicalFile.name)
        } finally {
            install("stop", "--supervisor", "nohup")
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
