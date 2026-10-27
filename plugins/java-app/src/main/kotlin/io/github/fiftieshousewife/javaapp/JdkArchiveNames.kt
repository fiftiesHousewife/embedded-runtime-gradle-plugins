package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.Target

/** Where a vendor publishes its archives: a base URL and an Ivy artifact pattern relative to it. */
data class JdkSite(val baseUrl: String, val pattern: String)

/** The exact-version form a vendor publishes under, and a real example of it for error messages. */
data class VersionForm(val regex: Regex, val example: String) {
    companion object {
        /** Oracle, Microsoft and SAP publish OpenJDK's version as it is. */
        val PLAIN = Regex("""\d+(\.\d+)*""")

        /** Temurin and Liberica put a `+build` in their URLs. */
        val WITH_BUILD = Regex("""\d+(\.\d+)*\+\d+""")
    }
}

/** How a vendor writes a target in its file names: `macos-aarch64`, `macosx_aarch64`, `win-amd64`. */
data class Spelling(
    val mac: String,
    val windows: String = "windows",
    val x64: String = "x64",
    val separator: String = "-",
) {
    fun of(target: Target): String {
        val os =
            when (target.os) {
                Os.LINUX -> "linux"
                Os.WINDOWS -> windows
                Os.MAC -> mac
            }
        val arch =
            when (target.arch) {
                Arch.X64 -> x64
                Arch.AARCH64 -> "aarch64"
            }
        return "$os$separator$arch"
    }

    companion object {
        val MACOS = Spelling(mac = "macos")
        val CORRETTO = Spelling(mac = "macosx")
        val ZULU = Spelling(mac = "macosx", windows = "win", separator = "_")
        val LIBERICA = Spelling(mac = "macos", x64 = "amd64")
    }
}

/** The common shape: an Ivy module, an artifact name, and the target in the classifier. */
data class ArchiveName(
    val module: String,
    val artifact: String,
    val spelling: Spelling,
    /** Corretto's Windows zip is `windows-x64-jdk.zip`, telling it apart from the MSI. */
    val windowsSuffix: String = "",
)
