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
     * `-` separates RPM's version from its release, so a Maven qualifier becomes a `~` pre-release, which
     * RPM sorts first just as Maven does: `1.2.0~SNAPSHOT` < `1.2.0`.
     */
    val rpmVersion: String get() = version.replace('-', '~').also {
        if (!VERSION.matches(it)) throw GradleException("'$version' cannot be expressed as an RPM version")
    }

    val arch: String get() = target.arch.rpm

    val fileName: String get() = "$name-$rpmVersion-$release.$arch.rpm"

    val home: String get() = "/opt/$name"
    val confDir: String get() = "/etc/$name"
    val dataDir: String get() = "/var/lib/$name"
    val logDir: String get() = "/var/log/$name"
    val unitFile: String get() = "/usr/lib/systemd/system/$name.service"

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

        /** LF whatever the checkout did: a CR would end up in every unit value and scriptlet line. */
        fun template(name: String): String =
            RpmLayout::class.java.getResource("/io/github/fiftieshousewife/appcore/rpm/$name")?.readText()
                ?.replace("\r\n", "\n")
                ?: error("RPM template $name missing from the app-core jar")
    }
}
