package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A Kotlin/JVM app needs nothing beyond `kotlin("jvm")` next to java-app: its classes land in the
 * same jar, kotlin-stdlib is an ordinary runtime dependency, and the Kotlin plugin compiles with the
 * toolchain java-app configures.
 *
 * The configuration cache is off here only: under TestKit's injected plugin classloader the Kotlin
 * plugin's build services fail to reload from the cache. Real builds, and the samples, keep it on.
 */
class KotlinAppTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `a Kotlin JVM app is packaged and smoke-tested like a Java one`() {
        val project = kotlinService()
        val result = project.build("build")
        val archive = project.project.resolve("build/distributions/ktsvc-1.0.0-${TestProject.hostTarget()}.tar.gz")
        val entries = TestProject.run("tar", "-tzf", archive.absolutePath)
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
            { assertTrue(entries.contains("ktsvc-1.0.0/lib/ktsvc-1.0.0.jar"), entries) },
            { assertTrue(entries.lines().any { it.startsWith("ktsvc-1.0.0/lib/kotlin-stdlib-") }, entries) },
        )
    }

    private fun kotlinService(): TestProject = TestProject(tmp).apply {
        JdkFixture.publish(this)
        gradleProperties(
            *JdkFixture.properties(this),
            "kotlin.compiler.execution.strategy" to "in-process",
            "org.gradle.configuration-cache" to "false",
        )
        settings(JavaAppSettingsPlugin.ID, "ktsvc")
        file(
            "build.gradle.kts",
            fixture("kotlin-service/build.gradle.kts.template")
                .replace("@KOTLIN@", KOTLIN_VERSION)
                .replace("@FEATURE@", JdkFixture.javaFeature.toString()),
        )
        file("src/main/kotlin/test/Main.kt", fixture("kotlin-service/Main.kt"))
    }

    private companion object {
        const val KOTLIN_VERSION = "2.4.21"
    }
}
