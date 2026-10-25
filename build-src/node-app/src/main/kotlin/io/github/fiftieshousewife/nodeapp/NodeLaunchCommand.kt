package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.shQuote

/** What the launcher starts: the script under `app/` and the fixed Node options. */
internal data class NodeLaunch(val entryPoint: String, val nodeArgs: List<String>)

/** The line in `bin/<app>` that starts Node.js. */
internal object NodeLaunchCommand {
    fun posix(launch: NodeLaunch) = listOf(
        "exec \"\$APP_HOME/runtime/bin/node\"",
        launch.nodeArgs.joinToString(" ") { shQuote(it) },
        "\${APP_NODE_OPTS:-}",
        "\"\$APP_HOME/app/${launch.entryPoint}\"",
        "\"\$@\"",
    ).filter(String::isNotEmpty).joinToString(" ")

    /** `%ARGS%` already starts with a space when it holds anything; see the launcher template. */
    fun windows(launch: NodeLaunch) = listOf(
        "\"%APP_HOME%\\runtime\\node.exe\"",
        launch.nodeArgs.joinToString(" ") { cmdQuote(it) },
        "%APP_NODE_OPTS%",
        "\"%APP_HOME%\\app\\${launch.entryPoint.replace('/', '\\')}\"%ARGS%",
    ).filter(String::isNotEmpty).joinToString(" ")
}
