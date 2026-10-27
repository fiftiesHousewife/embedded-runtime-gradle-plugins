package io.github.fiftieshousewife.javaapp

import org.gradle.jvm.application.scripts.JavaAppStartScriptGenerationDetails
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringWriter

class EmbeddedRuntimeScriptGeneratorTest {
    private val details =
        object : JavaAppStartScriptGenerationDetails {
            override fun getApplicationName() = "orders"
            override fun getOptsEnvironmentVar() = "ORDERS_OPTS"
            override fun getExitEnvironmentVar() = "ORDERS_EXIT_CONSOLE"
            override fun getMainClassName() = "com.example.Main"
            override fun getDefaultJvmOpts() = listOf("-Xss512k")
            override fun getClasspath() = listOf("lib/orders-1.0.jar", "lib/dependency-2.0.jar")
            override fun getModulePath() = emptyList<String>()
            override fun getScriptRelPath() = "bin/orders"
            override fun getAppNameSystemProperty() = "orders.appname"
            override fun getGitRef() = "v9.8.2"
        }

    private fun render(windows: Boolean, locked: Boolean = false) =
        StringWriter().also { EmbeddedRuntimeScriptGenerator(windows, locked).generateScript(details, it) }.toString()

    @Test
    fun `the POSIX script starts the embedded runtime with the app's jar first and Gradle's JVM options`() {
        val script = render(windows = false)
        assertAll(
            { assertTrue(script.startsWith("#!/bin/sh\n"), script) },
            { assertTrue(script.contains("exec \"\$APP_HOME/runtime/bin/java\" '-Xss512k'"), script) },
            { assertTrue(script.contains("\$APP_HOME/lib/orders-1.0.jar:\$APP_HOME/lib/*"), script) },
            { assertTrue(script.contains("'com.example.Main' \"\$@\""), script) },
            { assertFalse(script.contains("JAVA_HOME"), script) },
            { assertFalse(script.contains("\r"), script) },
        )
    }

    @Test
    fun `the Windows script uses CRLF and the embedded java exe`() {
        val script = render(windows = true)
        assertAll(
            { assertTrue(script.contains("\r\n"), script) },
            { assertTrue(script.contains("\"%APP_HOME%\\runtime\\bin\\java.exe\" \"-Xss512k\""), script) },
        )
    }

    @Test
    fun `locked runtime options are written into both scripts`() {
        assertAll(
            { assertTrue(render(windows = false, locked = true).contains("runtimeOptions = locked")) },
            { assertTrue(render(windows = true, locked = true).contains("runtimeOptions = locked")) },
            { assertFalse(render(windows = false).contains("runtimeOptions = locked")) },
        )
    }
}
