package io.github.fiftieshousewife.javaapp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JavaLaunchCommandTest {
    private val launch = JavaLaunch("com.example.Main", "svc-1.0.jar", listOf("-Xss512k"))

    @Test
    fun `the POSIX launcher puts the app's jar before the wildcard and passes every argument through`() {
        assertEquals(
            "exec \"\$APP_HOME/runtime/bin/java\" '-Xss512k' \${APP_JAVA_OPTS:-} " +
                "-cp \"\$APP_HOME/lib/svc-1.0.jar:\$APP_HOME/lib/*\" 'com.example.Main' \"\$@\"",
            JavaLaunchCommand.posix(launch),
        )
    }

    @Test
    fun `the Windows launcher appends the collected arguments straight after the main class`() {
        assertEquals(
            "\"%APP_HOME%\\runtime\\bin\\java.exe\" \"-Xss512k\" %APP_JAVA_OPTS% " +
                "-cp \"%APP_HOME%\\lib\\svc-1.0.jar;%APP_HOME%\\lib\\*\" com.example.Main%ARGS%",
            JavaLaunchCommand.windows(launch),
        )
    }

    @Test
    fun `no fixed JVM options leave no empty gap`() {
        assertEquals(
            "exec \"\$APP_HOME/runtime/bin/java\" \${APP_JAVA_OPTS:-} " +
                "-cp \"\$APP_HOME/lib/a.jar:\$APP_HOME/lib/*\" 'Main' \"\$@\"",
            JavaLaunchCommand.posix(JavaLaunch("Main", "a.jar", emptyList())),
        )
    }
}
