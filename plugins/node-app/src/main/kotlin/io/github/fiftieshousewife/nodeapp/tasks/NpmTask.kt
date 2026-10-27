package io.github.fiftieshousewife.nodeapp.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Runs npm with the pinned Node.js distribution, never one from `PATH`. */
@DisableCachingByDefault(because = "npm maintains its own cache; outputs are declared per task instance")
abstract class NpmTask
@Inject
constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val nodeHome: DirectoryProperty

    @get:Input abstract val nodeVersion: Property<String>

    @get:Internal abstract val workingDirectory: DirectoryProperty

    @get:Input abstract val npmArgs: ListProperty<String>

    @get:Input abstract val environment: MapProperty<String, String>

    /** Not fingerprinted: it carries credentials, and the registry it names does not change what npm installs. */
    @get:Internal abstract val npmrc: RegularFileProperty

    @TaskAction
    fun run() {
        val tools = NodeLayout.locate(nodeHome.get().asFile)
        exec.exec {
            workingDir(workingDirectory.get().asFile)
            environment(this@NpmTask.environment.get())
            NodeLayout.npm(this, tools, npmArgs.get(), npmrc.orNull?.asFile)
        }
    }
}
