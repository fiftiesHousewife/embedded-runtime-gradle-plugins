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

    /** For a spec copying the runtime's own tree: patterns match a file's path in its source, not its destination. */
    fun runtimeExecutableBits(spec: CopySpec) {
        val runtime = executables.filter { it.startsWith(RUNTIME) }.map { it.removePrefix(RUNTIME) }
        spec.filesMatching(runtime) { permissions { unix("rwxr-xr-x") } }
    }

    private const val RUNTIME = "runtime/"

    /** So a read-only runtime (a JDK's `legal/`) can be replaced; `permissions {}` alone would drop exec bits. */
    fun ownerWritable(spec: CopySpec) {
        spec.eachFile {
            val mode = permissions.toUnixNumeric() or OWNER_WRITE
            permissions { unix(mode) }
        }
    }

    /** A jar 0600 in the Gradle cache would be unreadable to the service account or OpenShift's user. */
    fun readableByAll(spec: CopySpec) {
        spec.eachFile {
            val mode = readable(permissions.toUnixNumeric())
            permissions { unix(mode) }
        }
    }

    internal fun readable(mode: Int): Int = mode or READ_ALL or if (mode and OWNER_EXECUTE != 0) EXECUTE_ALL else 0

    private const val OWNER_WRITE = 0b010_000_000
    private const val OWNER_EXECUTE = 0b001_000_000
    private const val READ_ALL = 0b100_100_100
    private const val EXECUTE_ALL = 0b001_001_001

    fun dropTopLevelDirectory(spec: CopySpec, replacement: String? = null) {
        spec.eachFile {
            val segments = listOfNotNull(replacement) + relativePath.segments.drop(1)
            relativePath = RelativePath.parse(!isDirectory, segments.joinToString("/"))
        }
        spec.includeEmptyDirs = false
    }
}
