package io.github.fiftieshousewife.platform.runtime

import org.gradle.api.GradleException
import java.io.Serializable

enum class Os(
    val id: String,
) {
    LINUX("linux"),
    WINDOWS("windows"),
    MAC("mac"),
}

enum class Arch(
    val id: String,
) {
    X64("x64"),
    AARCH64("aarch64"),
}

/**
 * A bundle target such as `linux-x64`. The id is the Maven classifier of the published bundle,
 * so it is part of the artefact contract and must never change meaning.
 */
data class Platform(
    val os: Os,
    val arch: Arch,
) : Serializable {
    val id: String get() = "${os.id}-${arch.id}"
    val isWindows: Boolean get() = os == Os.WINDOWS
    val archiveExtension: String get() = if (isWindows) "zip" else "tar.gz"

    override fun toString(): String = id

    companion object {
        private const val serialVersionUID = 1L

        val LINUX_X64 = Platform(Os.LINUX, Arch.X64)
        val LINUX_AARCH64 = Platform(Os.LINUX, Arch.AARCH64)
        val WINDOWS_X64 = Platform(Os.WINDOWS, Arch.X64)
        val MAC_X64 = Platform(Os.MAC, Arch.X64)
        val MAC_AARCH64 = Platform(Os.MAC, Arch.AARCH64)

        val SUPPORTED = listOf(LINUX_X64, LINUX_AARCH64, WINDOWS_X64, MAC_X64, MAC_AARCH64)

        fun parse(id: String): Platform =
            SUPPORTED.firstOrNull { it.id == id.trim().lowercase() }
                ?: throw GradleException("Unsupported platform '$id'. Supported: ${SUPPORTED.joinToString()}")

        /** The machine Gradle is running on. */
        fun host(): Platform {
            val osName = System.getProperty("os.name").lowercase()
            val os =
                when {
                    osName.startsWith("windows") -> Os.WINDOWS
                    osName.startsWith("mac") || osName.contains("darwin") -> Os.MAC
                    osName.contains("linux") -> Os.LINUX
                    else -> throw GradleException("Unsupported build host OS '$osName'")
                }
            val arch =
                when (val a = System.getProperty("os.arch").lowercase()) {
                    "amd64", "x86_64", "x64" -> Arch.X64
                    "aarch64", "arm64" -> Arch.AARCH64
                    else -> throw GradleException("Unsupported build host architecture '$a'")
                }
            return Platform(os, arch)
        }
    }
}
