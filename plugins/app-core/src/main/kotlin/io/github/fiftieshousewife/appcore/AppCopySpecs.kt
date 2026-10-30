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

    /** A jar 0600 in the Gradle cache would be unreadable to the service account or OpenShift's user. */
    fun readableByAll(spec: CopySpec) {
        spec.eachFile {
            val mode = readable(permissions.toUnixNumeric())
            permissions { unix(mode) }
        }
    }

    /**
     * 0755 or 0644 by the owner's execute bit alone, directories 0755: the runtime archive must be the same bytes
     * whatever the build machine's umask, and whoever unpacks it must be able to read it.
     */
    fun normalisedModes(spec: CopySpec) {
        spec.eachFile {
            val mode = normalised(permissions.toUnixNumeric())
            permissions { unix(mode) }
        }
        spec.dirPermissions { unix(EXECUTABLE) }
    }

    internal fun normalised(mode: Int): Int = if (mode and OWNER_EXECUTE != 0) EXECUTABLE else FILE

    private const val EXECUTABLE = 0b111_101_101
    private const val FILE = 0b110_100_100

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
