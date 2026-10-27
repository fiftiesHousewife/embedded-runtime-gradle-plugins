package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.Serializable

enum class Os(val id: String) {
    LINUX("linux"),
    WINDOWS("windows"),
    MAC("mac"),
}

/** [id] in target ids (`x64`), [rpm] in RPM and `uname -m` (`x86_64`), [container] in Docker (`amd64`). */
enum class Arch(val id: String, val rpm: String, val container: String) {
    X64("x64", "x86_64", "amd64"),
    AARCH64(ARM_64, ARM_64, "arm64"),
}

/** What Linux, RPM and the JDK call 64-bit ARM. */
private const val ARM_64 = "aarch64"

/** The id is the published archive's Maven classifier, so it must never change meaning. */
data class Target(val os: Os, val arch: Arch) : Serializable {
    val id: String get() = "${os.id}-${arch.id}"
    val isWindows: Boolean get() = os == Os.WINDOWS
    val archiveExtension: String get() = if (isWindows) "zip" else "tar.gz"

    /** `LinuxX64`, `MacAarch64`: appended to the names of tasks built once per target. */
    val taskSuffix: String get() = os.id.replaceFirstChar(Char::uppercase) + arch.id.replaceFirstChar(Char::uppercase)

    val installTaskName: String get() = "install${taskSuffix}Dist"

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
