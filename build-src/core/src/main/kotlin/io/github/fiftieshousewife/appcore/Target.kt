package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.Serializable

enum class Os(val id: String) {
    LINUX("linux"),
    WINDOWS("windows"),
    MAC("mac"),
}

enum class Arch(val id: String) {
    X64("x64"),
    AARCH64("aarch64"),
}

/**
 * The operating system and CPU an app archive is built for, such as `linux-x64`. The id is the Maven
 * classifier of the published archive, so it is part of the artefact contract and must never change
 * meaning.
 */
data class Target(val os: Os, val arch: Arch) : Serializable {
    val id: String get() = "${os.id}-${arch.id}"
    val isWindows: Boolean get() = os == Os.WINDOWS
    val archiveExtension: String get() = if (isWindows) "zip" else "tar.gz"

    /** `LinuxX64`, `MacAarch64`: appended to the names of tasks built once per target. */
    val taskSuffix: String get() = os.id.replaceFirstChar(Char::uppercase) + arch.id.replaceFirstChar(Char::uppercase)

    override fun toString(): String = id

    companion object {
        private const val serialVersionUID = 1L

        val LINUX_X64 = Target(Os.LINUX, Arch.X64)
        val LINUX_AARCH64 = Target(Os.LINUX, Arch.AARCH64)
        val WINDOWS_X64 = Target(Os.WINDOWS, Arch.X64)
        val MAC_X64 = Target(Os.MAC, Arch.X64)
        val MAC_AARCH64 = Target(Os.MAC, Arch.AARCH64)

        val SUPPORTED = listOf(LINUX_X64, LINUX_AARCH64, WINDOWS_X64, MAC_X64, MAC_AARCH64)

        /** A comma-separated list, as given on the command line: `linux-x64,windows-x64`. */
        fun parseList(ids: String): List<Target> = ids.split(',').filter(String::isNotBlank).map(::parse)

        fun parse(id: String): Target = SUPPORTED.firstOrNull { it.id == id.trim().lowercase() }
            ?: throw GradleException("Unsupported target '$id'. Supported: ${SUPPORTED.joinToString()}")

        /** The machine Gradle is running on. */
        fun host(): Target {
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
            return Target(os, arch)
        }
    }
}
