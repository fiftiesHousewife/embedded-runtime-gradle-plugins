package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Provider
import java.io.File

/**
 * A `securityPolicy` is a directory or zip, or the `group:name:version` of a zip, which normally comes
 * from a binary repository so the [RuntimePolicy] is versioned and reviewed like any release; a
 * machine's or CI agent's `-P<block>.securityPolicy` wins over the build.
 */
object RuntimePolicySource {
    private val coordinates = Regex("""[^/\\:]+:[^/\\:]+:[^/\\:]+""")

    internal fun isCoordinates(policy: String) = coordinates.matches(policy)

    fun files(project: Project, policy: Provider<String>): FileCollection {
        val projectDir = project.layout.projectDirectory
        val dependencies = project.dependencies
        val configurations = project.configurations
        // An absent policy contributes nothing, rather than failing when the collection is queried.
        val resolved = policy.map<List<Any>> {
            if (isCoordinates(it)) {
                listOf(
                    configurations.detachedConfiguration(dependencies.create("$it@zip")).apply {
                        isTransitive = false
                    },
                )
            } else {
                listOf(projectDir.file(it))
            }
        }.orElse(emptyList())
        return project.files(resolved)
    }

    fun load(files: Set<File>, fs: FileSystemOperations, archives: ArchiveOperations, scratch: File): RuntimePolicy? {
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
