package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Writes a text file whose content is a task input (used for the container Dockerfile). */
@CacheableTask
abstract class WriteTextFile : DefaultTask() {
    @get:Input abstract val text: Property<String>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() = outputFile.get().asFile.writeText(text.get())
}
