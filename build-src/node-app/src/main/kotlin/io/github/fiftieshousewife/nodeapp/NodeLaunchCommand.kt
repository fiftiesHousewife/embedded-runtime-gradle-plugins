package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.shQuote

/** What the launcher starts: the script under `app/` and the fixed Node options. */
internal data class NodeLaunch(val entryPoint: String, val nodeArgs: List<String>)

/**
 * The lines in `bin/<app>` that start Node.js: trust `security/trusted-cas.pem` when the archive has
 * one (from `nodeApp.security` or the runtime policy), then run the entry point.
 */
internal object NodeLaunchCommand {
    const val CA_BUNDLE = "security/trusted-cas.pem"
    private const val POSIX_CA_CERTS =
        "if [ -f \"\$APP_HOME/$CA_BUNDLE\" ]; then NODE_EXTRA_CA_CERTS=\"\$APP_HOME/$CA_BUNDLE\"; " +
            "export NODE_EXTRA_CA_CERTS; fi\n"
    private const val WINDOWS_CA_CERTS =
        "if exist \"%APP_HOME%\\security\\trusted-cas.pem\" " +
            "set \"NODE_EXTRA_CA_CERTS=%APP_HOME%\\security\\trusted-cas.pem\"\n"

    fun posix(launch: NodeLaunch) = POSIX_CA_CERTS + listOf(
        "exec \"\$APP_HOME/runtime/bin/node\"",
        launch.nodeArgs.joinToString(" ") { shQuote(it) },
        "\${APP_NODE_OPTS:-}",
        "\"\$APP_HOME/app/${launch.entryPoint}\"",
        "\"\$@\"",
    ).filter(String::isNotEmpty).joinToString(" ")

    /** `%ARGS%` already starts with a space when it holds anything; see the launcher template. */
    fun windows(launch: NodeLaunch) = WINDOWS_CA_CERTS + listOf(
        "\"%APP_HOME%\\runtime\\node.exe\"",
        launch.nodeArgs.joinToString(" ") { cmdQuote(it) },
        "%APP_NODE_OPTS%",
        "\"%APP_HOME%\\app\\${launch.entryPoint.replace('/', '\\')}\"%ARGS%",
    ).filter(String::isNotEmpty).joinToString(" ")
}
