package io.github.fiftieshousewife.appcore.tasks

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LockedOptionsTest {
    @TempDir
    lateinit var tmp: File

    /** Runs the locked lines under `sh` with [env], as the launcher would before starting the runtime. */
    private fun run(vararg env: Pair<String, String>): Pair<Int, String> {
        val script = tmp.resolve("locked.sh").apply {
            writeText("APP_NAME=svc\n" + LockedOptions.posix() + "echo started\n")
        }
        val process = ProcessBuilder("sh", script.absolutePath).redirectErrorStream(true).apply {
            environment().keys.removeIf { it.endsWith("_OPTIONS") || it.endsWith("_OPTS") }
            environment()["APP_CONF_DIR"] = tmp.resolve("conf").absolutePath
            environment().putAll(env)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a locked launcher starts with nothing set and refuses each way options could be added`() {
        tmp.resolve("other/jvm.options").apply { parentFile.mkdirs() }.writeText("-Xmx1g")
        val clean = run()
        val viaJavaTool = run("JAVA_TOOL_OPTIONS" to "-javaagent:x.jar")
        val viaNode = run("NODE_OPTIONS" to "--require ./x.js")
        tmp.resolve("conf/jvm.options").apply { parentFile.mkdirs() }.writeText("-Xmx1g")
        val viaFile = run()
        assertAll(
            { assertEquals(0 to "started\n", clean) },
            { assertEquals(LockedOptions.EXIT_CODE, viaJavaTool.first) },
            { assertTrue(viaJavaTool.second.contains("svc: JAVA_TOOL_OPTIONS is set"), viaJavaTool.second) },
            { assertTrue(viaNode.second.contains("NODE_OPTIONS is set"), viaNode.second) },
            { assertTrue(viaFile.second.contains("jvm.options exists"), viaFile.second) },
        )
    }

    @Test
    fun `the Windows lines echo no parentheses inside their blocks`() {
        val messages = LockedOptions.windows().lines().mapNotNull {
            Regex("""\(echo (.*) 1>&2""").find(it)?.groupValues?.get(1)
        }
        assertAll(
            { assertEquals(2, messages.size, LockedOptions.windows()) },
            { assertFalse(messages.any { '(' in it || ')' in it }, messages.toString()) },
        )
    }
}
