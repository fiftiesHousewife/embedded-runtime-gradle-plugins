package io.github.fiftieshousewife.javaapp

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JavaLaunchCommandTest {
    private val launch = JavaLaunch("com.example.Main", "svc-1.0.jar", listOf("-Xss512k"))

    @Test
    fun `the POSIX launcher puts the app's jar before the wildcard and passes every argument through`() {
        assertEquals(
            "exec \"\$APP_HOME/runtime/bin/java\" '-Xss512k' \${APP_JVM_OPTIONS_FILE:+\"\$APP_JVM_OPTIONS_FILE\"} " +
                "\${APP_JAVA_OPTS:-} " +
                "-cp \"\$APP_HOME/lib/svc-1.0.jar:\$APP_HOME/lib/*\" 'com.example.Main' \"\$@\"",
            JavaLaunchCommand.posix(launch).lines().last(),
        )
    }

    @Test
    fun `the Windows launcher appends the collected arguments straight after the main class`() {
        assertEquals(
            "\"%APP_HOME%\\runtime\\bin\\java.exe\" \"-Xss512k\" %APP_JVM_OPTIONS_FILE% %APP_JAVA_OPTS% " +
                "-cp \"%APP_HOME%\\lib\\svc-1.0.jar;%APP_HOME%\\lib\\*\" com.example.Main%ARGS%",
            JavaLaunchCommand.windows(launch).lines().last(),
        )
    }

    @Test
    fun `no fixed JVM options leave no empty gap`() {
        assertEquals(
            "exec \"\$APP_HOME/runtime/bin/java\" \${APP_JVM_OPTIONS_FILE:+\"\$APP_JVM_OPTIONS_FILE\"} " +
                "\${APP_JAVA_OPTS:-} " +
                "-cp \"\$APP_HOME/lib/a.jar:\$APP_HOME/lib/*\" 'Main' \"\$@\"",
            JavaLaunchCommand.posix(JavaLaunch("Main", "a.jar", emptyList())).lines().last(),
        )
    }

    @Test
    fun `an operator's jvm options file is used when present, on both launchers`() {
        assertAll(
            {
                assertEquals(
                    "if [ -f \"\$APP_CONF_DIR/jvm.options\" ]; " +
                        "then APP_JVM_OPTIONS_FILE=\"@\$APP_CONF_DIR/jvm.options\"; fi",
                    JavaLaunchCommand.posix(launch).lines()[1],
                )
            },
            {
                assertEquals(
                    "if exist \"%APP_CONF_DIR%\\jvm.options\" " +
                        "set APP_JVM_OPTIONS_FILE=\"@%APP_CONF_DIR%\\jvm.options\"",
                    JavaLaunchCommand.windows(launch).lines()[1],
                )
            },
        )
    }

    @Test
    fun `heap dumps and crash logs go to the log directory, which the POSIX launcher creates`() {
        val lines = JavaLaunchCommand.posix(launch.copy(logDirFiles = true)).lines()
        assertAll(
            {
                assertEquals(
                    "[ -d \"\$APP_LOG_DIR\" ] || (umask 027 && mkdir -p \"\$APP_LOG_DIR\") 2>/dev/null || true",
                    lines.first(),
                )
            },
            {
                assertEquals(
                    "exec \"\$APP_HOME/runtime/bin/java\" \"-XX:HeapDumpPath=\$APP_LOG_DIR\" " +
                        "\"-XX:ErrorFile=\$APP_LOG_DIR/hs_err_pid%p.log\" '-Xss512k'",
                    lines.last().substringBefore(" \${APP_JVM_OPTIONS_FILE"),
                )
            },
        )
    }

    @Test
    fun `the Windows launcher escapes the percent in the crash log name`() {
        val lines = JavaLaunchCommand.windows(launch.copy(logDirFiles = true)).lines()
        assertAll(
            { assertEquals("if not exist \"%APP_LOG_DIR%\" mkdir \"%APP_LOG_DIR%\" 2>nul", lines.first()) },
            {
                assertEquals(
                    "\"%APP_HOME%\\runtime\\bin\\java.exe\" \"-XX:HeapDumpPath=%APP_LOG_DIR%\" " +
                        "\"-XX:ErrorFile=%APP_LOG_DIR%\\hs_err_pid%%p.log\" \"-Xss512k\"",
                    lines.last().substringBefore(" %APP_JVM_OPTIONS_FILE%"),
                )
            },
        )
    }
}
