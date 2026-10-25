package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.shQuote

/** What the launcher starts: the main class, the app's own jar, and the fixed JVM options. */
internal data class JavaLaunch(val mainClass: String, val appJar: String, val jvmArgs: List<String>)

/**
 * The lines in `bin/<app>` that start the JVM. The app's own jar comes first on the classpath and
 * the lib directory wildcard after it, so the app's classes win over a duplicate in a dependency, which a
 * bare wildcard would leave to the JVM's directory order.
 *
 * JVM options come in this order, later ones winning: the build's `jvmArgs`, then an operator's
 * `$APP_CONF_DIR/jvm.options` (a JVM argument file) if there is one, then `$APP_JAVA_OPTS`.
 */
internal object JavaLaunchCommand {
    private const val POSIX_OPTIONS_FILE =
        "APP_JVM_OPTIONS_FILE=\n" +
            "if [ -f \"\$APP_CONF_DIR/jvm.options\" ]; then APP_JVM_OPTIONS_FILE=\"@\$APP_CONF_DIR/jvm.options\"; fi\n"

    private const val WINDOWS_OPTIONS_FILE =
        "set \"APP_JVM_OPTIONS_FILE=\"\n" +
            "if exist \"%APP_CONF_DIR%\\jvm.options\" set APP_JVM_OPTIONS_FILE=\"@%APP_CONF_DIR%\\jvm.options\"\n"

    fun posix(launch: JavaLaunch) = POSIX_OPTIONS_FILE + listOf(
        "exec \"\$APP_HOME/runtime/bin/java\"",
        launch.jvmArgs.joinToString(" ") { shQuote(it) },
        "\${APP_JVM_OPTIONS_FILE:+\"\$APP_JVM_OPTIONS_FILE\"}",
        "\${APP_JAVA_OPTS:-}",
        "-cp \"\$APP_HOME/lib/${launch.appJar}:\$APP_HOME/lib/*\"",
        shQuote(launch.mainClass),
        "\"\$@\"",
    ).filter(String::isNotEmpty).joinToString(" ")

    /** `%ARGS%` already starts with a space when it holds anything; see the launcher template. */
    fun windows(launch: JavaLaunch) = WINDOWS_OPTIONS_FILE + listOf(
        "\"%APP_HOME%\\runtime\\bin\\java.exe\"",
        launch.jvmArgs.joinToString(" ") { cmdQuote(it) },
        "%APP_JVM_OPTIONS_FILE%",
        "%APP_JAVA_OPTS%",
        "-cp \"%APP_HOME%\\lib\\${launch.appJar};%APP_HOME%\\lib\\*\"",
        launch.mainClass + "%ARGS%",
    ).filter(String::isNotEmpty).joinToString(" ")
}
