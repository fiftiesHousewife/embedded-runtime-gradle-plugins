package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.Serializable

/** What an RPM is called and where it puts things; see [RpmPackaging]. */
data class RpmLayout(
    val name: String,
    val version: String,
    val release: String,
    val target: Target,
    val user: String,
    val launcher: String,
    val summary: String,
) : Serializable {
    init {
        if (target.os != Os.LINUX) throw GradleException("RPMs are for Linux targets; $target is not one")
        if (!NAME.matches(name)) throw GradleException("'$name' is not a valid RPM package name")
        if (!USER.matches(user)) throw GradleException("'$user' is not a valid system account name")
        if (!RELEASE.matches(release)) throw GradleException("'$release' is not a valid RPM release")
    }

    /**
     * RPM versions cannot contain `-`, which separates version from release. A Maven qualifier
     * becomes a `~` pre-release, which RPM sorts before the release itself: `1.2.0~SNAPSHOT` <
     * `1.2.0`, just as `1.2.0-SNAPSHOT` < `1.2.0` in Maven.
     */
    val rpmVersion: String get() = version.replace('-', '~').also {
        if (!VERSION.matches(it)) throw GradleException("'$version' cannot be expressed as an RPM version")
    }

    /** The architecture name RPM and dnf use. */
    val arch: String get() = when (target.arch) {
        Arch.X64 -> "x86_64"
        Arch.AARCH64 -> "aarch64"
    }

    /** `<name>-<version>-<release>.<arch>.rpm`, the name every RPM tool expects. */
    val fileName: String get() = "$name-$rpmVersion-$release.$arch.rpm"

    val home: String get() = "/opt/$name"
    val confDir: String get() = "/etc/$name"
    val dataDir: String get() = "/var/lib/$name"
    val logDir: String get() = "/var/log/$name"
    val unitFile: String get() = "/usr/lib/systemd/system/$name.service"

    /** A packaged template (`app.service`, `pre.sh`, …) with this package's names filled in. */
    fun render(template: String): String = template
        .replace("@NAME@", name)
        .replace("@USER@", user)
        .replace("@LAUNCHER@", launcher)
        .replace("@SUMMARY@", summary)

    companion object {
        private const val serialVersionUID = 1L

        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
        private val USER = Regex("[a-z_][a-z0-9_-]{0,31}")
        private val RELEASE = Regex("[A-Za-z0-9._+~]+")
        private val VERSION = Regex("[A-Za-z0-9._+~^]+")

        fun template(name: String): String =
            RpmLayout::class.java.getResource("/io/github/fiftieshousewife/appcore/rpm/$name")?.readText()
                ?: error("RPM template $name missing from the app-core jar")
    }
}
