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

    private fun javaService(extraBuild: String = ""): TestProject =
        TestProject(tmp).apply {
            JdkFixture.publish(this, TestProject.hostTarget(), "linux-aarch64")
            gradleProperties(*JdkFixture.properties(this))
            settings(JavaAppSettingsPlugin.ID, "svc")
            file(
                "build.gradle.kts",
                """
            plugins { id("io.github.fiftieshousewife.java-app") }
            group = "com.example.test"
            version = providers.gradleProperty("appVersion").getOrElse("2.0.0")
            application { mainClass = "test.Main" }
            javaApp {
                javaVersion = ${JdkFixture.javaFeature}
                jlinkModules = listOf("java.base", "jdk.httpserver")
            }
            // The fixture JDK is whatever runs the tests, not necessarily Temurin.
            java { toolchain { vendor = JvmVendorSpec.matching("") } }
            $extraBuild
            """,
            )
            file(
                "src/main/java/test/Main.java",
                """
            package test;
            import com.sun.net.httpserver.HttpServer;
            import java.net.*;
            public class Main {
                public static void main(String[] args) throws Exception {
                    int port = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", "8080"));
                    if (args.length > 0 && args[0].equals("--healthcheck")) {
                        try { HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
                              System.exit(c.getResponseCode() == 200 ? 0 : 1); } catch (Exception e) { System.exit(1); }
                    }
                    HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
                    s.createContext("/", x -> { x.sendResponseHeaders(200, -1); x.close(); });
                    s.start();
                }
            }
            """,
            )
            file("src/deploy/conf/app.properties", "key=value")
        }

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

        // The generated Dockerfile copies app/, so the container context must hold the app there.
        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context")
        assertTrue(context.resolve("Dockerfile").isFile, "missing Dockerfile in $context")
        assertTrue(context.resolve("app/bin/svc").isFile, "the app must sit under app/ in $context")

        project.build("clean", "appTar")
        val checksum = sha256(archive)
        val rebuilt = project.build("clean", "appTar")
        assertTrue(rebuilt.output.contains("Reusing configuration cache"), rebuilt.output)
        assertEquals(checksum, sha256(archive), "rebuilding the same sources must produce an identical archive")
    }

    @Test
    fun `cross builds skip the smoke test and refuse to publish unless overridden`() {
        val project =
            javaService(
                """publishing { repositories { maven { name = "local"; url = uri(layout.buildDirectory.dir("repo")) } } }""",
            )
        val cross = arrayOf("-PjavaApp.target=linux-aarch64")

        val built = project.build("build", *cross)
        assertEquals(TaskOutcome.SKIPPED, built.task(":appSmokeTest")?.outcome)

        val refused = project.buildAndFail("publish", *cross)
        assertTrue(refused.output.contains("Refusing to publish: it is a cross build for linux-aarch64"), refused.output)

        project.build("publish", *cross, "-PjavaApp.allowUntestedPublish=true")
        val published = project.project.resolve("build/repo/com/example/test/svc/2.0.0")
        assertTrue(
            published.resolve("svc-2.0.0-linux-aarch64.tar.gz").isFile,
            "cross-built archive should be published with the override",
        )

        // The publication is a real software component, so Gradle Module Metadata is published and
        // names the target machine with Gradle's own attributes. That is what lets a consuming
        // Gradle build resolve the right archive instead of spelling out a classifier.
        val moduleMetadata = published.resolve("svc-2.0.0.module")
        assertTrue(moduleMetadata.isFile, "Gradle Module Metadata should be published alongside the POM")
        val gmm = moduleMetadata.readText()
        listOf(
            """"org.gradle.native.operatingSystem": "linux"""",
            """"org.gradle.native.architecture": "aarch64"""",
            """"org.gradle.dependency.bundling": "embedded"""",
            "svc-2.0.0-linux-aarch64.tar.gz",
        ).forEach { assertTrue(gmm.contains(it), "missing $it in\n$gmm") }
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
