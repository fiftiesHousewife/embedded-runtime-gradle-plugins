package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Provider
import java.io.File

/**
 * `securityPolicyArtifact` normally comes from a binary repository, so the [RuntimePolicy] is versioned and
 * reviewed like any release; a machine's or CI agent's `-P<block>.…` property wins over the build.
 */
object RuntimePolicySource {
    fun files(project: Project, path: Provider<String>, artifact: Provider<String>): FileCollection {
        val projectDir = project.layout.projectDirectory
        val dependencies = project.dependencies
        val configurations = project.configurations
        // Absent properties contribute nothing, rather than failing when the collection is queried.
        val fromPath = path.map<List<Any>> { listOf(projectDir.file(it)) }.orElse(emptyList())
        val fromArtifact =
            artifact.map<List<Any>> { coordinates ->
                listOf(
                    configurations.detachedConfiguration(dependencies.create("$coordinates@zip")).apply {
                        isTransitive = false
                    },
                )
            }.orElse(emptyList())
        return project.files(fromPath, fromArtifact)
    }

    fun load(files: Set<File>, fs: FileSystemOperations, archives: ArchiveOperations, scratch: File): RuntimePolicy? {
        if (files.size > 1) {
            throw GradleException("Set one of securityPolicy and securityPolicyArtifact, not both: $files")
        }
        val source = files.singleOrNull() ?: return null
        return RuntimePolicy.load(if (source.isDirectory) source else unzipped(source, fs, archives, scratch))
    }

    private fun unzipped(source: File, fs: FileSystemOperations, archives: ArchiveOperations, scratch: File): File {
        fs.sync {
            from(archives.zipTree(source))
            into(scratch)
        }
        return scratch.takeIf { it.resolve(RuntimePolicy.FILE).isFile }
            ?: scratch.listFiles()?.singleOrNull(File::isDirectory)
            ?: throw GradleException("${source.name} holds no ${RuntimePolicy.FILE}")
    }
}
