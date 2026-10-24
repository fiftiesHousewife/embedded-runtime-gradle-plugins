package io.github.fiftieshousewife.platform.runtime

import org.gradle.api.GradleException
import org.gradle.jvm.toolchain.JvmVendorSpec

/**
 * A JDK vendor and the download layout its builds are published under.
 *
 * Selected once per machine or project with `platform.runtime.jdk.distribution`; the base URL and
 * the Ivy artifact pattern can each be overridden on top of that
 * (`platform.public.jdkUrl`, `platform.public.jdkPattern`), which is how a layout this enum does
 * not know about — a mirror, an internal build, a vendor added later — is reached without a plugin
 * release.
 *
 * Both the compile toolchain and the bundled runtime come from the distribution chosen here, so
 * they can never disagree.
 *
 * Note on Oracle: its licence, not this plugin, decides whether you may redistribute a bundle with
 * an Oracle runtime inside it. Recent Oracle JDK releases under the NFTC permit it; OTN-licensed
 * releases do not, which is why the Oracle 17 and 21 lines stop receiving free archive builds while
 * 25 continues. Check the licence for the exact version you pin.
 */
enum class JdkDistribution(
    val id: String,
    /** Ivy organisation, and the top-level directory of the curated repository. */
    val group: String,
    /** What the Gradle toolchain spec must ask for to get this distribution. */
    val vendorSpec: JvmVendorSpec,
    val defaultBaseUrl: String,
    /** Ivy artifact pattern, relative to the base URL. */
    val defaultPattern: String,
) {
    /**
     * Adoptium's Temurin, published as GitHub release assets:
     * `.../temurin21-binaries/releases/download/jdk-21.0.13+9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz`
     */
    TEMURIN(
        id = "temurin",
        group = "temurin",
        vendorSpec = JvmVendorSpec.ADOPTIUM,
        defaultBaseUrl = "https://github.com/adoptium",
        defaultPattern = "[module]/releases/download/[revision]/[artifact].[ext]",
    ) {
        override fun requireVersion(version: JdkVersion) {
            if (version.build == null) {
                throw GradleException(
                    "Temurin version '$version' needs a build number, e.g. ${version.value}+9. " +
                        "The build number is part of the download URL.",
                )
            }
        }

        override fun artifact(
            version: JdkVersion,
            platform: Platform,
        ) = RuntimeArtifact(
            group = group,
            module = "temurin${version.feature}-binaries",
            revision = "jdk-${version.value}",
            artifact = "OpenJDK${version.feature}U-jdk_${platform.arch.id}_${platform.os.id}_hotspot_${version.underscored}",
            classifier = null,
            extension = platform.archiveExtension,
        )
    },

    /**
     * Oracle JDK, published as a flat archive tree:
     * `.../java/25/archive/jdk-25.0.2_linux-x64_bin.tar.gz`
     *
     * Oracle versions carry no build number, and Oracle spells macOS `macos` where Temurin uses
     * `mac`, so the classifier is built here rather than from [Platform.id].
     */
    ORACLE(
        id = "oracle",
        group = "oracle",
        vendorSpec = JvmVendorSpec.ORACLE,
        defaultBaseUrl = "https://download.oracle.com/java",
        defaultPattern = "[module]/archive/[artifact]-[revision]_[classifier]_bin.[ext]",
    ) {
        override fun requireVersion(version: JdkVersion) {
            if (version.build != null) {
                throw GradleException(
                    "Oracle version '$version' must not carry a build number; use ${version.withoutBuild()}.",
                )
            }
        }

        override fun artifact(
            version: JdkVersion,
            platform: Platform,
        ) = RuntimeArtifact(
            group = group,
            module = version.feature.toString(),
            revision = version.value,
            artifact = "jdk",
            classifier = "${oracleOs(platform)}-${platform.arch.id}",
            extension = platform.archiveExtension,
        )

        private fun oracleOs(platform: Platform) =
            when (platform.os) {
                Os.LINUX -> "linux"
                Os.WINDOWS -> "windows"
                Os.MAC -> "macos"
            }
    },
    ;

    /** How one archive of this distribution is addressed as an Ivy module plus explicit artifact. */
    abstract fun artifact(
        version: JdkVersion,
        platform: Platform,
    ): RuntimeArtifact

    /** Rejects a pin that this distribution could never publish, before any download is attempted. */
    abstract fun requireVersion(version: JdkVersion)

    companion object {
        val DEFAULT = TEMURIN

        fun parse(value: String): JdkDistribution =
            entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) }
                ?: throw GradleException(
                    "Unsupported JDK distribution '$value'. Supported: ${entries.joinToString { it.id }}. " +
                        "For any other vendor, keep one of these and override " +
                        "platform.public.jdkUrl and platform.public.jdkPattern.",
                )
    }
}
