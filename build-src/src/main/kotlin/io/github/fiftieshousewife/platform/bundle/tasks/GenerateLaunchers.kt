package io.github.fiftieshousewife.platform.bundle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes `bin/<app>` (POSIX `sh`) or `bin/<app>.bat` (Windows) from templates in the plugin jar.
 * `.bat` rather than `.cmd` is the extension Gradle's own `startScripts` produces.
 *
 * Line endings are fixed here (LF for sh, CRLF for bat) so the launcher is correct whatever the
 * developer's `core.autocrlf` setting — a CRLF shell launcher fails with `/bin/sh^M: bad interpreter`.
 */
@CacheableTask
abstract class GenerateLaunchers : DefaultTask() {
    @get:Input abstract val appName: Property<String>

    @get:Input abstract val windows: Property<Boolean>

    /** Shell fragment that `exec`s the runtime; may reference `$APP_HOME` and `"$@"`. */
    @get:Input abstract val posixExec: Property<String>

    /** Batch fragment that starts the runtime; may reference `%APP_HOME%` and `%ARGS%`. */
    @get:Input abstract val windowsExec: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.listFiles()?.forEach { it.deleteRecursively() }
        val name = appName.get()

        if (windows.get()) {
            val text =
                template("launcher.bat")
                    .replace("@APP_NAME@", name)
                    .replace("@EXEC@", windowsExec.get())
            out.resolve("$name.bat").writeText(text.lines().joinToString("\r\n"))
        } else {
            val text =
                template("launcher.sh")
                    .replace("@APP_NAME@", name)
                    .replace("@EXEC@", posixExec.get())
            out.resolve(name).apply {
                writeText(text.replace("\r\n", "\n"))
                setExecutable(true, false)
            }
        }
    }

    private fun template(name: String): String =
        GenerateLaunchers::class.java.getResource("/io/github/fiftieshousewife/platform/launcher/$name")?.readText()
            ?: error("Launcher template $name missing from plugin jar")

    companion object {
        /** Quotes a value for a POSIX shell word. */
        fun shQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

        /** Quotes a value for a Windows batch argument. */
        fun cmdQuote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
    }
}
