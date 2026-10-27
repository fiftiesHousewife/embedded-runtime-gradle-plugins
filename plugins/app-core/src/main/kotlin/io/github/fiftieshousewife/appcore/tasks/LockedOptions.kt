package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.RuntimeOptions

/**
 * The launcher lines for `runtimeOptions = locked`: exit 78 (`EX_CONFIG`) with a message naming the
 * variable or file, instead of starting a runtime someone has changed.
 */
internal object LockedOptions {
    const val EXIT_CODE = 78

    /** No parentheses: the Windows lines echo it inside a parenthesised block. */
    private const val REASON = "but this app's runtime options are locked by its build"

    private val variables = RuntimeOptions.VARIABLES.joinToString(" ")

    fun posix() = listOf(
        "# runtimeOptions = locked: the runtime starts with the options built into this launcher only.",
        "for var in $variables; do",
        "  eval \"value=\\\${\$var:-}\"",
        "  if [ -n \"\$value\" ]; then echo \"\$APP_NAME: \$var is set, $REASON\" >&2; exit $EXIT_CODE; fi",
        "done",
        "if [ -f \"\$APP_CONF_DIR/jvm.options\" ]; then",
        "  echo \"\$APP_NAME: \$APP_CONF_DIR/jvm.options exists, $REASON\" >&2",
        "  exit $EXIT_CODE",
        "fi",
    ).joinToString("\n", postfix = "\n")

    fun windows() = listOf(
        "rem runtimeOptions = locked: the runtime starts with the options built into this launcher only.",
        "for %%V in ($variables) do if defined %%V (echo %APP_NAME%: %%V is set, $REASON 1>&2 & exit /b $EXIT_CODE)",
        "if exist \"%APP_CONF_DIR%\\jvm.options\" " +
            "(echo %APP_NAME%: %APP_CONF_DIR%\\jvm.options exists, $REASON 1>&2 & exit /b $EXIT_CODE)",
    ).joinToString("\n", postfix = "\n")
}
