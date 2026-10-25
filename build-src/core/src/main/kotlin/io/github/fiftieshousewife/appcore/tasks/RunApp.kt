package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** `./gradlew runApp` — runs the assembled app exactly as a server or container would. */
@UntrackedTask(because = "Runs the application interactively")
abstract class RunApp
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Internal abstract val appDir: DirectoryProperty

        @get:Input abstract val appName: Property<String>

        @get:Input abstract val windows: Property<Boolean>

        @get:Input abstract val args: ListProperty<String>

        @TaskAction
        fun run() {
            val bin = appDir.get().asFile.resolve("bin")
            exec.exec {
                if (windows.get()) {
                    commandLine(
                        listOf("cmd.exe", "/c", bin.resolve("${appName.get()}.bat").absolutePath, "run") +
                            this@RunApp.args.get(),
                    )
                } else {
                    commandLine(listOf(bin.resolve(appName.get()).absolutePath, "run") + this@RunApp.args.get())
                }
                environment("APP_LOG_TARGET", System.getenv("APP_LOG_TARGET") ?: "stdout")
            }
        }
    }
