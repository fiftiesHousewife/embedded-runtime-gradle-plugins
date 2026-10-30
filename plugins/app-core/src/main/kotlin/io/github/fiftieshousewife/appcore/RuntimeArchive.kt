package io.github.fiftieshousewife.appcore

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

/**
 * `appRuntimeArchive<Target>`: `runtime/` alone, published as `<app>-runtime`. Nothing in it names the app or
 * its version, and its times, order and modes depend on nothing else, so an unchanged runtime is the same bytes on
 * every machine and every release.
 */
internal object RuntimeArchive {
    fun register(
        project: Project,
        wiring: AppWiring,
        target: Target,
        runtime: Any,
    ): TaskProvider<out AbstractArchiveTask> {
        val format = ArchiveFormat.nativeTo(target)
        val type = if (format == ArchiveFormat.ZIP) Zip::class.java else Tar::class.java
        return project.tasks.register("appRuntimeArchive${target.taskSuffix}", type) {
            group = wiring.taskGroup
            description = "Archives the ${target.id} runtime alone as <app>-runtime-<version>-${target.id}.$format"
            if (this is Tar) compression = Compression.GZIP
            archiveBaseName.set(wiring.extension.appName.map { "$it$SUFFIX" })
            archiveVersion.set(wiring.version)
            archiveClassifier.set(target.id)
            archiveExtension.set(format.extension)
            destinationDirectory.set(project.layout.buildDirectory.dir("distributions"))
            // Modes by path, never the file system's: a Windows agent has none.
            into(DIRECTORY) {
                from(runtime)
                AppCopySpecs.runtimeExecutableBits(this)
            }
        }
    }

    /** Installed with the app, so the installed app runs as it is. */
    fun installed(project: Project, runtime: Any) = project.copySpec {
        into(DIRECTORY) {
            from(runtime)
            AppCopySpecs.runtimeExecutableBits(this)
        }
        AppCopySpecs.readableByAll(this)
    }

    const val SUFFIX = "-runtime"

    const val DIRECTORY = "runtime"
}
