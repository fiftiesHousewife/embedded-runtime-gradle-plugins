package io.github.fiftieshousewife.nodeapp.tasks

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class NodeLayoutTest {
    @TempDir
    lateinit var dir: File

    private fun touch(path: String) = File(dir, path).also {
        Files.createDirectories(it.parentFile.toPath())
    }.apply { writeText("") }

    @Test
    fun `finds node and npm in the POSIX layout`() {
        touch("bin/node")
        val tools = NodeLayout.locate(dir)
        assertAll(
            { assertEquals(File(dir, "bin/node"), tools.node) },
            { assertEquals(File(dir, "lib/node_modules/npm/bin/npm-cli.js"), tools.npmCli) },
            { assertEquals(File(dir, "bin"), tools.binDir) },
        )
    }

    @Test
    fun `finds node and npm in the Windows layout`() {
        touch("node.exe")
        val tools = NodeLayout.locate(dir)
        assertAll(
            { assertEquals(File(dir, "node.exe"), tools.node) },
            { assertEquals(File(dir, "node_modules/npm/bin/npm-cli.js"), tools.npmCli) },
        )
    }

    @Test
    fun `refuses a directory with no Node in it`() {
        assertThrows(GradleException::class.java) { NodeLayout.locate(dir) }
    }

    @Test
    fun `pins npm to the generated config and keeps it quiet`() {
        assertEquals(
            listOf(
                "--userconfig=${File(dir, "npmrc").absolutePath}",
                "--no-audit",
                "--no-fund",
                "--no-update-notifier",
            ),
            NodeLayout.configArgs(File(dir, "npmrc")),
        )
    }

    @Test
    fun `knows whether package json defines a script`() {
        val packageJson = touch("package.json").apply { writeText("""{"scripts": {"test": "node --test"}}""") }
        assertAll(
            { assertTrue(NodeLayout.hasScript(packageJson, "test")) },
            { assertFalse(NodeLayout.hasScript(packageJson, "build")) },
            { assertFalse(NodeLayout.hasScript(File(dir, "missing.json"), "test")) },
        )
    }
}
