package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.GradleException
import org.gradle.jvm.toolchain.JvmVendorSpec

/** Where a vendor publishes its archives: a base URL and an Ivy artifact pattern relative to it. */
data class JdkSite(val baseUrl: String, val pattern: String)

/** The exact-version form a vendor publishes under, and a real example of it for error messages. */
data class VersionForm(val regex: Regex, val example: String) {
    companion object {
        /** OpenJDK's own `feature.interim.update.patch` with no build number: Oracle, Microsoft, SAP. */
        val PLAIN = Regex("""\d+(\.\d+)*""")

        /** The same plus a `+build` that the vendor puts in its URLs: Temurin, Liberica. */
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
        /** Oracle, Microsoft and SAP write `linux-x64`, `macos-aarch64`, `windows-x64`. */
        val MACOS = Spelling(mac = "macos")
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

/**
 * A JDK vendor and the download layout its builds are published under.
 *
 * Selected once per machine or project with `javaApp.jdk.distribution`; the base URL and the Ivy
 * artifact pattern can each be overridden on top of that (`javaApp.jdk.url`, `javaApp.jdk.pattern`),
 * which is how a layout this enum does not know about — a mirror, an internal build, a vendor added
 * later — is reached without a plugin release.
 *
 * Both the compile toolchain and the embedded runtime come from the distribution chosen here, so
 * they can never disagree. Every vendor spells its versions, operating systems and CPUs
 * differently, and the version is part of every download URL, so each entry owns its
 * [VersionForm] and checks a pin against it before anything is downloaded.
 *
 * Note on Oracle: its licence, not this plugin, decides whether you may redistribute an archive with
 * an Oracle runtime inside it. Recent Oracle JDK releases under the NFTC permit it; OTN-licensed
 * releases do not, which is why the Oracle 17 and 21 lines stop receiving free archive builds while
 * 25 continues. Check the licence for the exact version you pin.
 */
enum class JdkDistribution(
    val id: String,
    /** What the Gradle toolchain spec must ask for to get this distribution. */
    val vendorSpec: JvmVendorSpec,
    val site: JdkSite,
    val versionForm: VersionForm,
) {
    /**
     * Adoptium's Temurin, published as GitHub release assets:
     * `.../temurin21-binaries/releases/download/jdk-21.0.13+9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz`
     *
     * The target is encoded in the artifact name, so [RuntimeArtifact.classifier] is empty.
     */
    TEMURIN(
        "temurin",
        JvmVendorSpec.ADOPTIUM,
        JdkSite("https://github.com/adoptium", "[module]/releases/download/[revision]/[artifact].[ext]"),
        VersionForm(VersionForm.WITH_BUILD, "21.0.13+9"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) = adoptiumAsset(version, target, "jdk")

        /**
         * Temurin 24 and later are built with JEP 493 and ship without `jmods/`, which a cross link
         * needs; Adoptium publishes them as a separate archive beside each JDK:
         * `OpenJDK25U-jmods_x64_linux_hotspot_25.0.4.1_1.tar.gz`.
         */
        override fun separateJmods(feature: Int) = feature >= JEP_493_RELEASE

        override fun jmodsArtifact(version: JdkVersion, target: Target) = adoptiumAsset(version, target, "jmods")

        private fun adoptiumAsset(version: JdkVersion, target: Target, image: String) = RuntimeArtifact(
            group = id,
            module = "temurin${version.feature}-binaries",
            revision = "jdk-${version.value}",
            artifact =
            "OpenJDK${version.feature}U-${image}_${target.arch.id}_${target.os.id}_hotspot_${version.underscored}",
            classifier = null,
            extension = target.archiveExtension,
        )
    },

    /** Oracle JDK, published as a flat archive tree: `.../java/25/archive/jdk-25.0.2_linux-x64_bin.tar.gz` */
    ORACLE(
        "oracle",
        JvmVendorSpec.ORACLE,
        JdkSite(
            "https://download.oracle.com/java",
            "[module]/archive/[artifact]-[revision]_[classifier]_bin.[ext]",
        ),
        VersionForm(VersionForm.PLAIN, "25.0.3"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) =
            archive(ArchiveName(version.feature.toString(), "jdk", Spelling.MACOS), version, target)
    },

    /**
     * Amazon Corretto, published on its own CDN under a five-part version that adds Amazon's own
     * build and revision to the OpenJDK one:
     * `.../downloads/resources/21.0.13.0.10/amazon-corretto-21.0.13.0.10-macosx-aarch64.tar.gz`
     */
    CORRETTO(
        "corretto",
        JvmVendorSpec.AMAZON,
        JdkSite("https://corretto.aws/downloads/resources", "[revision]/[artifact]-[revision]-[classifier].[ext]"),
        VersionForm(Regex("""\d+(\.\d+){4}"""), "21.0.13.0.10"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) = archive(
            ArchiveName("corretto-${version.feature}", "amazon-corretto", CORRETTO_SPELLING, windowsSuffix = "-jdk"),
            version,
            target,
        )
    },

    /**
     * Azul Zulu, published on Azul's CDN. Its file names carry both Azul's own version and the
     * OpenJDK one, and only the pair identifies a build, so the pin is Azul's spelling of both:
     * `21.54.107-ca-jdk21.0.13` for `.../zulu/bin/zulu21.54.107-ca-jdk21.0.13-macosx_aarch64.tar.gz`.
     * `ca` is Azul's free community build; the commercial ones are not on the public CDN.
     */
    ZULU(
        "zulu",
        JvmVendorSpec.AZUL,
        JdkSite("https://cdn.azul.com/zulu", "bin/[artifact][revision]-[classifier].[ext]"),
        VersionForm(Regex("""\d+(\.\d+)+-ca-jdk\d+(\.\d+)*"""), "21.54.107-ca-jdk21.0.13"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) =
            archive(ArchiveName("zulu${version.feature}", "zulu", ZULU_SPELLING), version, target)
    },

    /**
     * BellSoft Liberica (the standard JDK, not Lite or Full), published as GitHub release assets
     * with Debian's `amd64` for x64:
     * `.../Liberica/releases/download/21.0.13+11/bellsoft-jdk21.0.13+11-macos-aarch64.tar.gz`
     */
    LIBERICA(
        "liberica",
        JvmVendorSpec.BELLSOFT,
        JdkSite(
            "https://github.com/bell-sw",
            "[module]/releases/download/[revision]/[artifact][revision]-[classifier].[ext]",
        ),
        VersionForm(VersionForm.WITH_BUILD, "21.0.13+11"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) =
            archive(ArchiveName("Liberica", "bellsoft-jdk", Spelling(mac = "macos", x64 = "amd64")), version, target)
    },

    /**
     * Microsoft Build of OpenJDK, behind Microsoft's `aka.ms` redirector:
     * `https://aka.ms/download-jdk/microsoft-jdk-21.0.12-macos-aarch64.tar.gz`
     *
     * The redirector answers a version Microsoft never released with a web page rather than a 404,
     * so a mistyped pin fails at extraction rather than at download. Dependency verification
     * (`gradle/verification-metadata.xml`) catches it before that.
     */
    MICROSOFT(
        "microsoft",
        JvmVendorSpec.MICROSOFT,
        JdkSite("https://aka.ms/download-jdk", "[artifact]-[revision]-[classifier].[ext]"),
        VersionForm(VersionForm.PLAIN, "21.0.12"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) =
            archive(ArchiveName("microsoft-jdk", "microsoft-jdk", Spelling.MACOS), version, target)

        override val coverage = "Microsoft builds long-term-support releases only (11, 17, 21, 25)."
    },

    /**
     * SAP's SapMachine, published as GitHub release assets in Oracle's file-name style:
     * `.../SapMachine/releases/download/sapmachine-21.0.13/sapmachine-jdk-21.0.13_macos-aarch64_bin.tar.gz`
     *
     * SapMachine 11 is out of support and SapMachine 25 has no Intel macOS build.
     */
    SAPMACHINE(
        "sapmachine",
        JvmVendorSpec.SAP,
        JdkSite(
            "https://github.com/SAP",
            "[module]/releases/download/sapmachine-[revision]/[artifact]-[revision]_[classifier]_bin.[ext]",
        ),
        VersionForm(VersionForm.PLAIN, "21.0.13"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) =
            archive(ArchiveName("SapMachine", "sapmachine-jdk", Spelling.MACOS), version, target)
    },
    ;

    /** Ivy organisation, and the top-level directory of the curated repository. */
    val group: String get() = id

    /** How one archive of this distribution is addressed as an Ivy module plus explicit artifact. */
    abstract fun artifact(version: JdkVersion, target: Target): RuntimeArtifact

    /** Rejects a pin that this distribution could never publish, before any download is attempted. */
    fun requireVersion(version: JdkVersion) {
        if (!versionForm.regex.matches(version.value)) {
            throw GradleException(
                "'$version' is not a $id version. $id versions look like ${versionForm.example}, and the whole " +
                    "version is part of the download URL.",
            )
        }
    }

    /** Which feature releases the vendor builds, when that is not every one; shown when a pin is missing. */
    open val coverage: String = ""

    /** Whether [feature]'s JDK archives lack `jmods/`, which this vendor publishes separately. */
    open fun separateJmods(feature: Int): Boolean = false

    /** The separate `jmods/` archive, for a release where [separateJmods] is true. */
    open fun jmodsArtifact(version: JdkVersion, target: Target): RuntimeArtifact =
        throw GradleException("$id publishes jmods/ inside its JDK archives")

    /** The common case: the version is the revision as-is and the target goes in the classifier. */
    protected fun archive(name: ArchiveName, version: JdkVersion, target: Target) = RuntimeArtifact(
        group = group,
        module = name.module,
        revision = version.value,
        artifact = name.artifact,
        classifier = name.spelling.of(target) + if (target.isWindows) name.windowsSuffix else "",
        extension = target.archiveExtension,
    )

    companion object {
        val DEFAULT = TEMURIN

        /** JEP 493 (JDK 24) lets a vendor ship a JDK without `jmods/`. */
        private const val JEP_493_RELEASE = 24

        fun parse(value: String): JdkDistribution =
            entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) }
                ?: throw GradleException(
                    "Unsupported JDK distribution '$value'. Supported: ${entries.joinToString { it.id }}. " +
                        "For any other vendor, keep one of these and override javaApp.jdk.url and javaApp.jdk.pattern.",
                )
    }
}

/** Amazon writes `linux-x64`, `macosx-aarch64`, `windows-x64`. */
private val CORRETTO_SPELLING = Spelling(mac = "macosx")

/** Azul writes `linux_x64`, `macosx_aarch64`, `win_x64`. */
private val ZULU_SPELLING = Spelling(mac = "macosx", windows = "win", separator = "_")
