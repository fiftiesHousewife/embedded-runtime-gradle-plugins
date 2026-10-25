package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate.shQuote

/**
 * What the launcher starts: the script under `app/` and the fixed Node options, and whether the
 * archive carries `security/trusted-cas.pem` for `NODE_EXTRA_CA_CERTS`.
 */
internal data class NodeLaunch(val entryPoint: String, val nodeArgs: List<String>, val extraCaCerts: Boolean = false)

/** The line in `bin/<app>` that starts Node.js. */
internal object NodeLaunchCommand {
    private const val POSIX_CA_CERTS =
        "NODE_EXTRA_CA_CERTS=\"\$APP_HOME/security/trusted-cas.pem\"; export NODE_EXTRA_CA_CERTS\n"
    private const val WINDOWS_CA_CERTS = "set \"NODE_EXTRA_CA_CERTS=%APP_HOME%\\security\\trusted-cas.pem\"\n"

    fun posix(launch: NodeLaunch) = (if (launch.extraCaCerts) POSIX_CA_CERTS else "") + listOf(
        "exec \"\$APP_HOME/runtime/bin/node\"",
        launch.nodeArgs.joinToString(" ") { shQuote(it) },
        "\${APP_NODE_OPTS:-}",
        "\"\$APP_HOME/app/${launch.entryPoint}\"",
        "\"\$@\"",
    ).filter(String::isNotEmpty).joinToString(" ")

    /** `%ARGS%` already starts with a space when it holds anything; see the launcher template. */
    fun windows(launch: NodeLaunch) = (if (launch.extraCaCerts) WINDOWS_CA_CERTS else "") + listOf(
        "\"%APP_HOME%\\runtime\\node.exe\"",
        launch.nodeArgs.joinToString(" ") { cmdQuote(it) },
        "%APP_NODE_OPTS%",
        "\"%APP_HOME%\\app\\${launch.entryPoint.replace('/', '\\')}\"%ARGS%",
    ).filter(String::isNotEmpty).joinToString(" ")
}
