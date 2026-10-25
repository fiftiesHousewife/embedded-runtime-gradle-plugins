package io.github.fiftieshousewife.appcore.tasks

/**
 * The launcher every app gets, `bin/<app>` (POSIX `sh`) or `bin/<app>.bat`, from templates in the
 * plugin jar: `run`, `healthcheck` and `version`, the `APP_ROOT` layout, and the line that starts
 * the runtime. Java renders it through Gradle's `startScripts` (`EmbeddedRuntimeScriptGenerator`),
 * Node through [GenerateLaunchers].
 *
 * Line endings are fixed here (LF for sh, CRLF for bat) so the launcher is correct whatever the
 * developer's `core.autocrlf` setting: a CRLF shell launcher fails with `/bin/sh^M: bad interpreter`.
 */
object LauncherTemplate {
    /**
     * [exec] starts the runtime: a shell fragment that may use `$APP_HOME` and `"$@"`, or a batch
     * fragment that may use `%APP_HOME%` and `%ARGS%`.
     */
    fun render(windows: Boolean, appName: String, lockedOptions: Boolean, exec: String): String {
        val policy = when {
            !lockedOptions -> ""
            windows -> LockedOptions.windows()
            else -> LockedOptions.posix()
        }
        val text = template(if (windows) "launcher.bat" else "launcher.sh")
            .replace("@APP_NAME@", appName)
            .replace("@POLICY@", policy)
            .replace("@EXEC@", exec)
            .replace("\r\n", "\n")
        return if (windows) text.lines().joinToString("\r\n") else text
    }

    private fun template(name: String): String =
        LauncherTemplate::class.java.getResource("/io/github/fiftieshousewife/appcore/launcher/$name")?.readText()
            ?: error("Launcher template $name missing from plugin jar")

    /** Quotes a value for a POSIX shell word. */
    fun shQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    /** Quotes a value for a Windows batch argument. */
    fun cmdQuote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
