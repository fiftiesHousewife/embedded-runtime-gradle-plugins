package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.InstallLayout
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.archiveTree
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.time.Duration
import javax.inject.Inject

/**
 * Installs this machine's archive into an [InstallLayout]: unpacks it as a new release, seeds
 * `conf/`, starts it once against that configuration and waits for its health check, and only then
 * switches `current` to it (the old one becomes `previous`), runs the restart command and prunes
 * old releases. A release that fails its health check is deleted and never becomes current.
 */
@DisableCachingByDefault(because = "Installs onto this machine")
abstract class AppInstall
@Inject
constructor(
    private val fs: FileSystemOperations,
    private val archives: ArchiveOperations,
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFiles: ConfigurableFileCollection

    @get:Internal abstract val installDir: DirectoryProperty

    @get:Input abstract val keep: Property<Int>

    @get:Input abstract val restartCommand: ListProperty<String>

    @get:Input abstract val healthTimeout: Property<Duration>

    @TaskAction
    fun install() {
        val layout = InstallLayout(installDir.get().asFile)
        val release = unpack(layout)
        if (release.canonicalFile == layout.current?.canonicalFile) {
            logger.lifecycle("${release.name} is already installed and current in ${layout.root}")
            return
        }
        layout.seedConfig(release).forEach { logger.lifecycle("Seeded conf/$it") }
        verify(layout, release)
        layout.switchTo(release)
        logger.lifecycle("current -> releases/${release.name}")
        Restart.run(exec, restartCommand.get())
        layout.prune(keep.get()).forEach { logger.lifecycle("Pruned release $it") }
    }

    /** Unpacks the archive as `releases/<app>-<version>`, replacing a copy that is not current. */
    private fun unpack(layout: InstallLayout): File {
        val archive = archiveFiles.files.singleOrNull()
            ?: throw GradleException("appInstall needs exactly one archive for this machine, got ${archiveFiles.files}")
        val staging = layout.releases.resolve(".staging")
        fs.sync {
            from(archives.archiveTree(archive))
            into(staging)
        }
        val top = staging.listFiles()?.singleOrNull(File::isDirectory)
            ?: throw GradleException("$archive must contain exactly one top-level directory")
        val manifest = forThisMachine(AppManifest.read(top), archive)
        val release = layout.release(manifest.name, manifest.version)
        if (release.canonicalFile != layout.current?.canonicalFile) {
            release.deleteRecursively()
            check(top.renameTo(release)) { "Could not move $top to $release" }
        }
        staging.deleteRecursively()
        return release
    }

    private fun forThisMachine(manifest: AppManifest, archive: File): AppManifest {
        val host = Target.host()
        if (manifest.target != host.id) {
            throw GradleException("${archive.name} is for ${manifest.target}; this machine is $host")
        }
        return manifest
    }

    private fun verify(layout: InstallLayout, release: File) {
        val manifest = AppManifest.read(release)
        val windows = Target.host().isWindows
        val launcher = release.resolve(if (windows) "bin/${manifest.name}.bat" else "bin/${manifest.name}")
        val env = mapOf("APP_CONF_DIR" to layout.conf.absolutePath)
        val result = SmokeRun(launcher, windows, temporaryDir, healthTimeout.get()).run(manifest.version, env)
        if (result is SmokeRun.Result.Failed) {
            release.deleteRecursively()
            throw GradleException(
                "${release.name} ${result.reason}; it was not installed and current is unchanged:\n${result.tail}",
            )
        }
        logger.lifecycle("${release.name} started against ${layout.conf} and reported healthy")
    }
}
