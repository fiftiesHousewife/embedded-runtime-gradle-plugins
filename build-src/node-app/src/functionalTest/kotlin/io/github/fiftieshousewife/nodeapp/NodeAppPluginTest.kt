package io.github.fiftieshousewife.nodeapp

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

class NodeAppPluginTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `builds, tests and smoke-tests a Node app with the pinned runtime`() {
        val project = webApp()
        val result = project.build("build")
        val archive = project.project.resolve("build/distributions/web-3.1.0-${TestProject.hostTarget()}.tar.gz")
        val entries = TestProject.run("tar", "-tzf", archive.absolutePath).lines()
        val outcomes = listOf(":npmTest", ":appSmokeTest").map { task ->
            { assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome) }
        }
        val present = LAYOUT.map { path ->
            { assertTrue(entries.contains("web-3.1.0-${TestProject.hostTarget()}/$path"), "missing $path") }
        }
        val testsLeftOut = { assertFalse(entries.any { it.contains("server.test.mjs") }, "tests must not be packaged") }
        assertAll(checks(outcomes + present + testsLeftOut))
    }

    private fun webApp() = TestProject(tmp).apply {
        NodeFixture.publish(this)
        gradleProperties(*NodeFixture.properties(this))
        settings(NodeAppSettingsPlugin.ID, "web")
        listOf(
            "build.gradle.kts.template",
            "package.json",
            "package-lock.json",
            "src/server.mjs",
            "test/server.test.mjs",
        )
            .forEach { file(it.removeSuffix(".template"), fixture("web/$it")) }
    }

    private fun fixture(path: String) = NodeAppPluginTest::class.java.getResource("/fixtures/$path")!!.readText()

    private companion object {
        val LAYOUT = listOf("bin/web", "runtime/bin/node", "app/server.mjs", "app/package.json")
    }
}
