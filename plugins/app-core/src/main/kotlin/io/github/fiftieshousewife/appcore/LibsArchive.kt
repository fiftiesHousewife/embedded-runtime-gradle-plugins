package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

/**
 * `appLibsArchive<Target>`: third-party libraries alone, published as `<app>-libs`, under their paths in the app
 * (`lib/`, `app/node_modules/`), so it unpacks into the app's directory as the runtime archive does. It names
 * neither the app nor its version, so unchanged libraries are the same bytes. A marker keeps it from being empty.
 */
internal class LibsArchive(private val project: Project, private val wiring: AppWiring) {
    private val marker: TaskProvider<WriteTextFile> =
        project.tasks.register("appLibsMarker", WriteTextFile::class.java) {
            description = "Writes the file every libraries archive holds, so none is empty"
            text.set("The app's third-party libraries.\n")
            outputFile.set(wiring.workDir.map { it.file("libs/$MARKER") })
        }

    fun register(target: Target, spec: EcosystemSpec): TaskProvider<out AbstractArchiveTask> {
        val format = ArchiveFormat.nativeTo(target)
        val type = if (format == ArchiveFormat.ZIP) Zip::class.java else Tar::class.java
        return project.tasks.register("appLibsArchive${target.taskSuffix}", type) {
            group = wiring.taskGroup
            description = "Archives the ${target.id} libraries alone as <app>-libs-<version>-${target.id}.$format"
            if (this is Tar) compression = Compression.GZIP
            archiveBaseName.set(wiring.extension.appName.map { "$it$SUFFIX" })
            archiveVersion.set(wiring.version)
            archiveClassifier.set(target.id)
            archiveExtension.set(format.extension)
            destinationDirectory.set(project.layout.buildDirectory.dir("distributions"))
            into(spec.librariesDirectory) {
                spec.libraries(this)
                from(marker)
            }
            // A library's exec bit stays, as a native tool needs it; the rest of the mode is never the machine's.
            useFileSystemPermissions()
            dirPermissions { unix("rwxr-xr-x") }
            AppCopySpecs.fixedModes(this)
        }
    }

    companion object {
        /** Installed with the app, so the installed app runs as it is. */
        fun installed(project: Project, spec: EcosystemSpec) = project.copySpec {
            into(spec.librariesDirectory) { spec.libraries(this) }
            AppCopySpecs.readableByAll(this)
        }

        /** Whether [topDirectory] is a libraries archive's: an app archive's has a version in it. */
        fun holds(topDirectory: String) = topDirectory in TOP_DIRECTORIES

        const val SUFFIX = "-libs"
        const val MARKER = ".libs"
        private val TOP_DIRECTORIES = setOf("lib", "app")
    }
}
