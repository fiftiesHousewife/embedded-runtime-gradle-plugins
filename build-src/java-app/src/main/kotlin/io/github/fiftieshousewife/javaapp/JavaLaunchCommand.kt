package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.shQuote

/**
 * What the launcher starts: the main class, the app's own jar, the fixed JVM options, and whether
 * heap dumps and crash logs go to `$APP_LOG_DIR` ([JavaRuntimeDefaults.heapDumpOnOutOfMemory]).
 */
internal data class JavaLaunch(
    val mainClass: String,
    val appJar: String,
    val jvmArgs: List<String>,
    val logDirFiles: Boolean = false,
)

/**
 * The lines in `bin/<app>` that start the JVM. The app's own jar comes first on the classpath and
 * the lib directory wildcard after it, so the app's classes win over a duplicate in a dependency, which a
 * bare wildcard would leave to the JVM's directory order.
 *
 * JVM options come in this order, later ones winning: where dumps and crash logs go, the build's
 * `jvmArgs` (after [JavaRuntimeDefaults]), then an operator's `$APP_CONF_DIR/jvm.options` (a JVM
 * argument file) if there is one, then `$APP_JAVA_OPTS`.
 */
internal object JavaLaunchCommand {
    /**
     * The log directory, created owner-and-group only when missing, so a heap dump has somewhere to
     * go. Never fatal: an account that can't create it (a read-only install, a sandboxed service)
     * still starts the app, which then loses only its heap dump.
     */
    private const val POSIX_LOG_DIR =
        "[ -d \"\$APP_LOG_DIR\" ] || (umask 027 && mkdir -p \"\$APP_LOG_DIR\") 2>/dev/null || true\n"
    private const val WINDOWS_LOG_DIR = "if not exist \"%APP_LOG_DIR%\" mkdir \"%APP_LOG_DIR%\" 2>nul\n"
    private const val POSIX_LOG_OPTIONS =
        "\"-XX:HeapDumpPath=\$APP_LOG_DIR\" \"-XX:ErrorFile=\$APP_LOG_DIR/hs_err_pid%p.log\""

    /** A batch file reads `%%` as one `%`. */
    private const val WINDOWS_LOG_OPTIONS =
        "\"-XX:HeapDumpPath=%APP_LOG_DIR%\" \"-XX:ErrorFile=%APP_LOG_DIR%\\hs_err_pid%%p.log\""

    private const val POSIX_OPTIONS_FILE =
        "APP_JVM_OPTIONS_FILE=\n" +
            "if [ -f \"\$APP_CONF_DIR/jvm.options\" ]; then APP_JVM_OPTIONS_FILE=\"@\$APP_CONF_DIR/jvm.options\"; fi\n"

    private const val WINDOWS_OPTIONS_FILE =
        "set \"APP_JVM_OPTIONS_FILE=\"\n" +
            "if exist \"%APP_CONF_DIR%\\jvm.options\" set APP_JVM_OPTIONS_FILE=\"@%APP_CONF_DIR%\\jvm.options\"\n"

    fun posix(launch: JavaLaunch) = (if (launch.logDirFiles) POSIX_LOG_DIR else "") + POSIX_OPTIONS_FILE + listOf(
        "exec \"\$APP_HOME/runtime/bin/java\"",
        if (launch.logDirFiles) POSIX_LOG_OPTIONS else "",
        launch.jvmArgs.joinToString(" ") { shQuote(it) },
        "\${APP_JVM_OPTIONS_FILE:+\"\$APP_JVM_OPTIONS_FILE\"}",
        "\${APP_JAVA_OPTS:-}",
        "-cp \"\$APP_HOME/lib/${launch.appJar}:\$APP_HOME/lib/*\"",
        shQuote(launch.mainClass),
        "\"\$@\"",
    ).filter(String::isNotEmpty).joinToString(" ")

    /** `%ARGS%` already starts with a space when it holds anything; see the launcher template. */
    fun windows(launch: JavaLaunch) = (if (launch.logDirFiles) WINDOWS_LOG_DIR else "") + WINDOWS_OPTIONS_FILE + listOf(
        "\"%APP_HOME%\\runtime\\bin\\java.exe\"",
        if (launch.logDirFiles) WINDOWS_LOG_OPTIONS else "",
        launch.jvmArgs.joinToString(" ") { cmdQuote(it) },
        "%APP_JVM_OPTIONS_FILE%",
        "%APP_JAVA_OPTS%",
        "-cp \"%APP_HOME%\\lib\\${launch.appJar};%APP_HOME%\\lib\\*\"",
        launch.mainClass + "%ARGS%",
    ).filter(String::isNotEmpty).joinToString(" ")
}
