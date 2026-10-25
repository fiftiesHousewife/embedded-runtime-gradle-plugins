package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.InstallLayout
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Switches an [InstallLayout] back to its previous release, then runs the restart command. */
@DisableCachingByDefault(because = "Changes what runs on this machine")
abstract class AppRollback
@Inject
constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val installDir: DirectoryProperty

    @get:Input abstract val restartCommand: ListProperty<String>

    @TaskAction
    fun rollback() {
        val layout = InstallLayout(installDir.get().asFile)
        layout.rollback()
        logger.lifecycle("current -> releases/${layout.current?.name}")
        Restart.run(exec, restartCommand.get())
    }
}

/** Runs the configured restart command, if any. */
internal object Restart {
    fun run(exec: ExecOperations, command: List<String>) {
        if (command.isEmpty()) return
        exec.exec { commandLine(command) }
    }
}
