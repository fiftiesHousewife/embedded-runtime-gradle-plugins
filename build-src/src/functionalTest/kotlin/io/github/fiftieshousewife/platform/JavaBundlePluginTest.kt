package io.github.fiftieshousewife.platform

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class JavaBundlePluginTest {
    @TempDir
    lateinit var tmp: File

    private fun javaService(extraBuild: String = ""): TestProject =
        TestProject(tmp).apply {
            fakeJdk(TestProject.hostPlatform(), "linux-aarch64")
            gradleProperties()
            settings("svc")
            file(
                "build.gradle.kts",
                """
            plugins { id("io.github.fiftieshousewife.platform.java-bundle") }
            group = "com.example.test"
            version = "2.0.0"
            application { mainClass = "test.Main" }
            bundle {
                javaVersion = $javaFeature
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
            file("src/bundle/conf/app.properties", "key=value")
        }

    @Test
    fun `builds, smoke-tests and reproduces a self-contained bundle`() {
        val project = javaService()
        val first = project.build("build")
        assertEquals(TaskOutcome.SUCCESS, first.task(":bundleSmokeTest")?.outcome)

        val archive = project.project.resolve("build/distributions/svc-2.0.0-${TestProject.hostPlatform()}.tar.gz")
        val entries = TestProject.run("tar", "-tvzf", archive.absolutePath)
        listOf(
            "svc-2.0.0/bin/svc",
            "svc-2.0.0/runtime/bin/java",
            "svc-2.0.0/lib/svc-2.0.0.jar",
            "svc-2.0.0/conf.defaults/app.properties",
            "svc-2.0.0/META-INF/bundle.json",
            "svc-2.0.0/META-INF/sbom.cdx.json",
        ).forEach { assertTrue(entries.contains(it), "missing $it in\n$entries") }
        assertTrue(entries.lines().any { it.startsWith("-rwxr-xr-x") && it.endsWith("svc-2.0.0/bin/svc") }, entries)
        assertTrue(entries.lines().any { it.startsWith("-rwxr-xr-x") && it.endsWith("runtime/lib/jspawnhelper") }, entries)

        // The generated Dockerfile copies app/, so the container context must hold the bundle there.
        project.build("bundleDockerContext")
        val context = project.project.resolve("build/docker-context")
        assertTrue(context.resolve("Dockerfile").isFile, "missing Dockerfile in $context")
        assertTrue(context.resolve("app/bin/svc").isFile, "bundle must sit under app/ in $context")

        project.build("clean", "bundleTar")
        val checksum = sha256(archive)
        val rebuilt = project.build("clean", "bundleTar")
        assertTrue(rebuilt.output.contains("Reusing configuration cache"), rebuilt.output)
        assertEquals(checksum, sha256(archive), "rebuilding the same sources must produce an identical archive")
    }

    @Test
    fun `cross builds skip the smoke test and refuse to publish unless overridden`() {
        val project =
            javaService(
                """publishing { repositories { maven { name = "local"; url = uri(layout.buildDirectory.dir("repo")) } } }""",
            )
        val cross = arrayOf("-Pbundle.targetPlatform=linux-aarch64")

        val built = project.build("build", *cross)
        assertEquals(TaskOutcome.SKIPPED, built.task(":bundleSmokeTest")?.outcome)

        val refused = project.buildAndFail("publish", *cross)
        assertTrue(refused.output.contains("Refusing to publish: it is a cross build for linux-aarch64"), refused.output)

        project.build("publish", *cross, "-Pbundle.allowUntestedPublish=true")
        val published = project.project.resolve("build/repo/com/example/test/svc/2.0.0")
        assertTrue(
            published.resolve("svc-2.0.0-linux-aarch64.tar.gz").isFile,
            "cross-built bundle should be published with the override",
        )

        // The publication is a real software component, so Gradle Module Metadata is published and
        // names the target machine with Gradle's own attributes. That is what lets a consuming
        // Gradle build resolve the right bundle instead of spelling out a classifier.
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

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
