package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Writes Node's `bin/<app>` or `bin/<app>.bat` from [LauncherTemplate]; Java's come from `startScripts`. */
@CacheableTask
abstract class GenerateLaunchers : DefaultTask() {
    @get:Input abstract val appName: Property<String>

    @get:Input abstract val windows: Property<Boolean>

    /** Shell fragment that `exec`s the runtime; may reference `$APP_HOME` and `"$@"`. */
    @get:Input abstract val posixExec: Property<String>

    /** Batch fragment that starts the runtime; may reference `%APP_HOME%` and `%ARGS%`. */
    @get:Input abstract val windowsExec: Property<String>

    /** `runtimeOptions = locked`: refuse to start while anything would add runtime options. */
    @get:Input abstract val lockedOptions: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.listFiles()?.forEach { it.deleteRecursively() }
        val name = appName.get()
        val windows = windows.get()
        val exec = if (windows) windowsExec.get() else posixExec.get()
        val text = LauncherTemplate.render(windows, name, lockedOptions.get(), exec)
        out.resolve(if (windows) "$name.bat" else name).apply {
            writeText(text)
            setExecutable(true, false)
        }
    }
}
