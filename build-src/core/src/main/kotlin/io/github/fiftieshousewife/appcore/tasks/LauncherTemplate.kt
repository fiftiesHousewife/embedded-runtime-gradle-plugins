package io.github.fiftieshousewife.appcore.tasks

/** Line endings are fixed whatever `core.autocrlf` says: a CRLF shell launcher fails with `bad interpreter`. */
object LauncherTemplate {
    /** [exec] may use `$APP_HOME` and `"$@"` in sh, `%APP_HOME%` and `%ARGS%` in batch. */
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

    fun shQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    fun cmdQuote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
