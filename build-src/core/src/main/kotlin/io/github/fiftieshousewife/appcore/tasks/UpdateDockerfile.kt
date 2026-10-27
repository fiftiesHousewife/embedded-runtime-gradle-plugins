package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.ManagedDockerfile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/** Only the plugins' block ([ManagedDockerfile]) is rewritten; a Dockerfile without one is the project's alone. */
@DisableCachingByDefault(because = "Edits a file in the project")
abstract class UpdateDockerfile : DefaultTask() {
    /** What belongs in the plugins' block. */
    @get:Input abstract val managedBlock: Property<String>

    @get:OutputFile abstract val dockerfile: RegularFileProperty

    @TaskAction
    fun update() {
        val file = dockerfile.get().asFile
        val existing = file.takeIf(File::isFile)?.readText()
        val updated = ManagedDockerfile.update(existing, managedBlock.get())
        when {
            updated == null -> logger.info("$file has no embedded-runtime block, so it is used as it is")

            updated == existing -> logger.info("$file is up to date")

            else -> {
                file.parentFile.mkdirs()
                file.writeText(updated)
                logger.lifecycle(
                    if (existing ==
                        null
                    ) {
                        "Created $file"
                    } else {
                        "Updated the embedded-runtime block in $file"
                    },
                )
            }
        }
    }
}
