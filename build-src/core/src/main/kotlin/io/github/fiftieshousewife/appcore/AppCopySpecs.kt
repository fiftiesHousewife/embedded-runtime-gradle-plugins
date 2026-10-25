package io.github.fiftieshousewife.appcore

import org.gradle.api.file.CopySpec
import org.gradle.api.file.RelativePath

/** How the app directory's files are copied, wherever they are copied to. */
object AppCopySpecs {
    /**
     * The files that must be executable, relative to the app directory. Exec bits are set
     * explicitly rather than trusted from the filesystem: a Windows checkout or a jlink run on
     * Windows has none, and a missing bit on `jspawnhelper` breaks `ProcessBuilder` on Linux at
     * runtime, long after the build succeeded.
     */
    val executables = listOf("bin/**", "runtime/bin/**", "runtime/lib/jspawnhelper", "runtime/lib/jexec")

    /** Also inside an archive's top-level directory. */
    private val executablePatterns = executables + executables.map { "*/$it" }

    /** Whether [path], relative to the app directory, is one of [executables]. */
    fun isExecutable(path: String): Boolean =
        executables.any { if (it.endsWith("/**")) path.startsWith(it.removeSuffix("**")) else path == it }

    fun executableBits(spec: CopySpec) {
        spec.filesMatching(executablePatterns) { permissions { unix("rwxr-xr-x") } }
    }

    /**
     * Strips an archive's single top-level directory (`node-v24.../`, `<app>-<version>/`) while
     * copying, or renames it to [replacement].
     */
    fun dropTopLevelDirectory(spec: CopySpec, replacement: String? = null) {
        spec.eachFile {
            val segments = listOfNotNull(replacement) + relativePath.segments.drop(1)
            relativePath = RelativePath.parse(!isDirectory, segments.joinToString("/"))
        }
        spec.includeEmptyDirs = false
    }
}
