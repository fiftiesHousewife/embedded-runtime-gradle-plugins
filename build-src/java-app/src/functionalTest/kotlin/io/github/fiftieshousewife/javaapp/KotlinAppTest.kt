package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A Kotlin/JVM app needs nothing beyond `kotlin("jvm")` next to java-app: its classes land in the
 * same jar, kotlin-stdlib is an ordinary runtime dependency, and the Kotlin plugin compiles with the
 * toolchain java-app configures. This test proves all three on a real build, with the Kotlin plugin
 * and stdlib resolved from the plugin portal and Maven Central.
 */
class KotlinAppTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `a Kotlin JVM app is packaged and smoke-tested like a Java one`() {
        val project = kotlinService()
        val result = project.build("build")

        assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome)
        val archive = project.project.resolve("build/distributions/ktsvc-1.0.0-${TestProject.hostTarget()}.tar.gz")
        val entries = TestProject.run("tar", "-tzf", archive.absolutePath)
        assertTrue(entries.contains("ktsvc-1.0.0/lib/ktsvc-1.0.0.jar"), entries)
        assertTrue(entries.lines().any { it.startsWith("ktsvc-1.0.0/lib/kotlin-stdlib-") }, entries)
    }

    private fun kotlinService(): TestProject =
        TestProject(tmp).apply {
            JdkFixture.publish(this)
            gradleProperties(
                *JdkFixture.properties(this),
                // The Kotlin daemon cannot always start where the tests run (sandboxes, locked-down CI).
                "kotlin.compiler.execution.strategy" to "in-process",
                // TestKit loads the plugins under test in their own classloader, and the Kotlin plugin's
                // build services then fail to reload from the configuration cache. A real build is
                // unaffected (the samples build Kotlin with the cache on); this is a TestKit artefact.
                "org.gradle.configuration-cache" to "false",
            )
            settings(JavaAppSettingsPlugin.ID, "ktsvc")
            file(
                "build.gradle.kts",
                """
                plugins {
                    kotlin("jvm") version "$KOTLIN_VERSION"
                    id("io.github.fiftieshousewife.java-app")
                }
                version = "1.0.0"
                application { mainClass = "test.MainKt" }
                javaApp {
                    javaVersion = ${JdkFixture.javaFeature}
                    jlinkModules = listOf("java.base", "jdk.httpserver")
                }
                java { toolchain { vendor = JvmVendorSpec.matching("") } }
                """,
            )
            file(
                "src/main/kotlin/test/Main.kt",
                """
                package test

                import com.sun.net.httpserver.HttpServer
                import java.net.HttpURLConnection
                import java.net.InetSocketAddress
                import java.net.URI
                import kotlin.system.exitProcess

                fun main(args: Array<String>) {
                    val port = System.getenv("APP_PORT")?.toInt() ?: 8080
                    if (args.firstOrNull() == "--healthcheck") {
                        val ok = runCatching {
                            (URI("http://127.0.0.1:${'$'}port/").toURL().openConnection() as HttpURLConnection).responseCode == 200
                        }.getOrDefault(false)
                        exitProcess(if (ok) 0 else 1)
                    }
                    HttpServer.create(InetSocketAddress(port), 0).apply {
                        createContext("/") { it.sendResponseHeaders(200, -1); it.close() }
                        start()
                    }
                }
                """,
            )
        }

    private companion object {
        const val KOTLIN_VERSION = "2.4.21"
    }
}
