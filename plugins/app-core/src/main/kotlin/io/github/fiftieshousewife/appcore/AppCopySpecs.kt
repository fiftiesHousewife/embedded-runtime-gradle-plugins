package io.github.fiftieshousewife.appcore

import org.gradle.api.file.CopySpec
import org.gradle.api.file.RelativePath

object AppCopySpecs {
    /** Set explicitly: Windows checkouts and jlink runs have none, and `ProcessBuilder` needs `jspawnhelper`'s. */
    val executables = listOf("bin/**", "runtime/bin/**", "runtime/lib/jspawnhelper", "runtime/lib/jexec")

    private val executablePatterns = executables + executables.map { "*/$it" }

    fun isExecutable(path: String): Boolean =
        executables.any { if (it.endsWith("/**")) path.startsWith(it.removeSuffix("**")) else path == it }

    fun executableBits(spec: CopySpec) {
        spec.filesMatching(executablePatterns) { permissions { unix("rwxr-xr-x") } }
    }

    /** So a read-only runtime (a JDK's `legal/`) can be replaced; `permissions {}` alone would drop exec bits. */
    fun ownerWritable(spec: CopySpec) {
        spec.eachFile {
            val mode = permissions.toUnixNumeric() or OWNER_WRITE
            permissions { unix(mode) }
        }
    }

    private const val OWNER_WRITE = 0b010_000_000

    fun dropTopLevelDirectory(spec: CopySpec, replacement: String? = null) {
        spec.eachFile {
            val segments = listOfNotNull(replacement) + relativePath.segments.drop(1)
            relativePath = RelativePath.parse(!isDirectory, segments.joinToString("/"))
        }
        spec.includeEmptyDirs = false
    }
}
