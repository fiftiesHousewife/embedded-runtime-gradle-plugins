package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.RuntimeFingerprint
import io.github.fiftieshousewife.appcore.Sha256
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/** Writes [RuntimeFingerprint]: published beside the runtime archive, it names nothing of the app either. */
@CacheableTask
abstract class WriteRuntimeFingerprint : DefaultTask() {
    @get:Input abstract val entries: MapProperty<String, String>

    /** Each file, or each file under a directory, is listed by its path within it. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val files = mutableListOf<Pair<String, String>>()
        sources.asFileTree.visit {
            if (!isDirectory) files += relativePath.pathString to Sha256.of(file.toPath())
        }
        Files.writeString(outputFile.get().asFile.toPath(), RuntimeFingerprint.render(entries.get(), files))
    }
}
