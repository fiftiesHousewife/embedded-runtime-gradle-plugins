package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.RuntimeArchive
import io.github.fiftieshousewife.appcore.RuntimeFingerprint
import io.github.fiftieshousewife.appcore.Sha256
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files

/**
 * Compares each target's runtime with the previous release's: its fingerprint and archive from the repository,
 * this build's from its own tasks. Fails when a fingerprint is unchanged but the archive is not, since the
 * repository would then store the same runtime twice.
 */
@DisableCachingByDefault(because = "Compares against what the repository holds now")
abstract class VerifyRuntimeFingerprint : DefaultTask() {
    @get:Input abstract val targets: ListProperty<String>

    /** The release compared with, as given: a version or `latest.release`. */
    @get:Input abstract val previousRelease: Property<String>

    /** The previous release's runtime archives and fingerprints, those the repository has. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val previous: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val current: ConfigurableFileCollection

    /** Why the previous release could not be read, other than its not having a target. */
    @get:Input abstract val unreadable: ListProperty<String>

    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        requireReadable()
        val before = previous.files.toList()
        val now = current.files.toList()
        val compared = targets.get().map { target ->
            val release = release(now, target) ?: throw GradleException("No $target runtime archive was built")
            Comparison(target, release(before, target), release)
        }
        val report = compared.joinToString("\n", postfix = "\n") { it.description }
        Files.writeString(reportFile.get().asFile.toPath(), report)
        logger.lifecycle("The runtime against ${previousRelease.get()}:\n$report".trimEnd())
        val duplicated = compared.filter { it.verdict == RuntimeFingerprint.Verdict.Duplicated }
        if (duplicated.isNotEmpty()) throw GradleException(duplicated.joinToString("\n") { it.description })
    }

    private fun requireReadable() {
        val reasons = unreadable.get()
        if (reasons.isNotEmpty()) {
            throw GradleException("Could not read ${previousRelease.get()}'s runtimes: ${reasons.joinToString("; ")}")
        }
    }

    private class Comparison(
        target: String,
        previous: RuntimeFingerprint.Release?,
        current: RuntimeFingerprint.Release,
    ) {
        val verdict = RuntimeFingerprint.compare(previous, current)
        val description = RuntimeFingerprint.describe(target, previous, current)
    }

    /** [target]'s fingerprint and archive among [files]: `<app>-runtime-<version>-<target>…`. */
    private fun release(files: List<File>, target: String): RuntimeFingerprint.Release? {
        val suffix = "-$target${RuntimeFingerprint.CLASSIFIER_SUFFIX}.${RuntimeFingerprint.EXTENSION}"
        val fingerprint = files.firstOrNull { it.name.endsWith(suffix) } ?: return null
        val stem = fingerprint.name.removeSuffix(suffix)
        val archive = files.firstOrNull { it != fingerprint && it.name.startsWith("$stem-$target.") } ?: return null
        return RuntimeFingerprint.Release(
            version = stem.substringAfterLast("${RuntimeArchive.SUFFIX}-"),
            fingerprint = Files.readAllLines(fingerprint.toPath()).filter(String::isNotBlank).toSet(),
            archiveSha256 = Sha256.of(archive.toPath()),
        )
    }
}
