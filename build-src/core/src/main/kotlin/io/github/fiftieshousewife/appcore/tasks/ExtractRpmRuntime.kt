package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.RpmPayload
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/**
 * Extracts a runtime packaged as an RPM: unpacks the whole package, finds the runtime's home inside
 * it (wherever the package installs it), and leaves that home's contents in [outputDir], as the
 * archive extraction leaves a `.tar.gz`'s.
 */
@CacheableTask
abstract class ExtractRpmRuntime : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rpm: RegularFileProperty

    /** A file only the runtime's home holds: `release` for a JDK. */
    @get:Input abstract val marker: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val staging = temporaryDir.resolve("payload").apply { deleteRecursively() }
        val rpmFile = rpm.get().asFile
        RpmPayload.unpack(rpmFile, staging)
        val home = RpmPayload.home(staging, marker.get(), rpmFile.name)
        val out = outputDir.get().asFile.apply { deleteRecursively() }
        Files.move(home.toPath(), out.toPath())
        staging.deleteRecursively()
    }
}
