package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.shQuote

/** What the launcher starts: the main class, the app's own jar, and the fixed JVM options. */
internal data class JavaLaunch(val mainClass: String, val appJar: String, val jvmArgs: List<String>)

/**
 * The line in `bin/<app>` that starts the JVM. The app's own jar comes first on the classpath and
 * the lib directory wildcard after it, so the app's classes win over a duplicate in a dependency, which a
 * bare wildcard would leave to the JVM's directory order.
 */
internal object JavaLaunchCommand {
    fun posix(launch: JavaLaunch) = listOf(
        "exec \"\$APP_HOME/runtime/bin/java\"",
        launch.jvmArgs.joinToString(" ") { shQuote(it) },
        "\${APP_JAVA_OPTS:-}",
        "-cp \"\$APP_HOME/lib/${launch.appJar}:\$APP_HOME/lib/*\"",
        shQuote(launch.mainClass),
        "\"\$@\"",
    ).filter(String::isNotEmpty).joinToString(" ")

    /** `%ARGS%` already starts with a space when it holds anything; see the launcher template. */
    fun windows(launch: JavaLaunch) = listOf(
        "\"%APP_HOME%\\runtime\\bin\\java.exe\"",
        launch.jvmArgs.joinToString(" ") { cmdQuote(it) },
        "%APP_JAVA_OPTS%",
        "-cp \"%APP_HOME%\\lib\\${launch.appJar};%APP_HOME%\\lib\\*\"",
        launch.mainClass + "%ARGS%",
    ).filter(String::isNotEmpty).joinToString(" ")
}
