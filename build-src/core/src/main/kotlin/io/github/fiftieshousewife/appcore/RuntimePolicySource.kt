package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import java.io.File

/**
 * Where a build gets the [RuntimePolicy] from: `-P<block>.securityPolicy`, a directory or zip on
 * disk, or `-P<block>.securityPolicyArtifact`, the `group:name:version` of a zip in the build's
 * Maven repositories (normally BinaryRepository, so the policy is versioned and reviewed like any
 * release). Set it per machine or CI agent, in `~/.gradle/gradle.properties` or as an
 * `ORG_GRADLE_PROJECT_…` variable, where it overrides anything a project sets.
 */
object RuntimePolicySource {
    fun files(project: Project, block: String): FileCollection {
        val providers = project.providers
        val path = providers.gradleProperty("$block.securityPolicy")
        val artifact = providers.gradleProperty("$block.securityPolicyArtifact")
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

    /** The policy in [files] (a directory or a zip), or null when none is configured. */
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
