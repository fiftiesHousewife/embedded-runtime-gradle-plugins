package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.GradleException
import org.gradle.jvm.toolchain.JvmVendorSpec

/**
 * A JDK vendor and the download layout its builds are published under.
 *
 * Selected once per machine or project with `javaApp.jdk.distribution`; the base URL and the
 * Ivy artifact pattern can each be overridden on top of that (`javaApp.jdk.url`,
 * `javaApp.jdk.pattern`), which is how a layout this enum does
 * not know about — a mirror, an internal build, a vendor added later — is reached without a plugin
 * release.
 *
 * Both the compile toolchain and the bundled runtime come from the distribution chosen here, so
 * they can never disagree.
 *
 * Every vendor spells its versions, operating systems and CPUs differently, and the version is part of every
 * download URL, so each entry owns its [versionFormat] and checks a pin against it before anything
 * is downloaded.
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
    val defaultBaseUrl: String,
    /** Ivy artifact pattern, relative to the base URL. */
    val defaultPattern: String,
    /** The exact-version form this vendor publishes under. */
    private val versionFormat: Regex,
    /** A real version in [versionFormat], quoted in error messages. */
    val exampleVersion: String,
) {
    /**
     * Adoptium's Temurin, published as GitHub release assets:
     * `.../temurin21-binaries/releases/download/jdk-21.0.13+9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz`
     *
     * The target is encoded in the artifact name, so [RuntimeArtifact.classifier] is empty.
     */
    TEMURIN(
        id = "temurin",
        vendorSpec = JvmVendorSpec.ADOPTIUM,
        defaultBaseUrl = "https://github.com/adoptium",
        defaultPattern = "[module]/releases/download/[revision]/[artifact].[ext]",
        versionFormat = WITH_BUILD,
        exampleVersion = "21.0.13+9",
    ) {
        override fun artifact(
            version: JdkVersion,
            target: Target,
        ) = RuntimeArtifact(
            group = id,
            module = "temurin${version.feature}-binaries",
            revision = "jdk-${version.value}",
            artifact = "OpenJDK${version.feature}U-jdk_${target.arch.id}_${target.os.id}_hotspot_${version.underscored}",
            classifier = null,
            extension = target.archiveExtension,
        )
    },

    /**
     * Oracle JDK, published as a flat archive tree:
     * `.../java/25/archive/jdk-25.0.2_linux-x64_bin.tar.gz`
     */
    ORACLE(
        id = "oracle",
        vendorSpec = JvmVendorSpec.ORACLE,
        defaultBaseUrl = "https://download.oracle.com/java",
        defaultPattern = "[module]/archive/[artifact]-[revision]_[classifier]_bin.[ext]",
        versionFormat = PLAIN,
        exampleVersion = "25.0.3",
    ) {
        override fun artifact(
            version: JdkVersion,
            target: Target,
        ) = archive(module = version.feature.toString(), version, "jdk", classifier(target, mac = "macos"), target)
    },

    /**
     * Amazon Corretto, published on its own CDN under a five-part version that adds Amazon's own
     * build and revision to the OpenJDK one:
     * `.../downloads/resources/21.0.13.0.10/amazon-corretto-21.0.13.0.10-macosx-aarch64.tar.gz`
     *
     * Windows is the odd one out, with a `-jdk` suffix that tells the zip apart from the MSI.
     */
    CORRETTO(
        id = "corretto",
        vendorSpec = JvmVendorSpec.AMAZON,
        defaultBaseUrl = "https://corretto.aws/downloads/resources",
        defaultPattern = "[revision]/[artifact]-[revision]-[classifier].[ext]",
        versionFormat = Regex("""\d+(\.\d+){4}"""),
        exampleVersion = "21.0.13.0.10",
    ) {
        override fun artifact(
            version: JdkVersion,
            target: Target,
        ) = archive(
            module = "corretto-${version.feature}",
            version,
            "amazon-corretto",
            classifier(target, mac = "macosx") + if (target.isWindows) "-jdk" else "",
            target,
        )
    },

    /**
     * Azul Zulu, published on Azul's CDN. Its file names carry both Azul's own version and the
     * OpenJDK one, and only the pair identifies a build, so the pin is Azul's spelling of both:
     * `21.54.107-ca-jdk21.0.13` for
     * `.../zulu/bin/zulu21.54.107-ca-jdk21.0.13-macosx_aarch64.tar.gz`
     *
     * `ca` is Azul's free community build; the commercial ones are not on the public CDN.
     */
    ZULU(
        id = "zulu",
        vendorSpec = JvmVendorSpec.AZUL,
        defaultBaseUrl = "https://cdn.azul.com/zulu",
        defaultPattern = "bin/[artifact][revision]-[classifier].[ext]",
        versionFormat = Regex("""\d+(\.\d+)+-ca-jdk\d+(\.\d+)*"""),
        exampleVersion = "21.54.107-ca-jdk21.0.13",
    ) {
        override fun artifact(
            version: JdkVersion,
            target: Target,
        ) = archive(
            module = "zulu${version.feature}",
            version,
            "zulu",
            classifier(target, mac = "macosx", windows = "win", separator = "_"),
            target,
        )
    },

    /**
     * BellSoft Liberica (the standard JDK, not Lite or Full), published as GitHub release assets
     * with Debian's `amd64` for x64:
     * `.../Liberica/releases/download/21.0.13+11/bellsoft-jdk21.0.13+11-macos-aarch64.tar.gz`
     */
    LIBERICA(
        id = "liberica",
        vendorSpec = JvmVendorSpec.BELLSOFT,
        defaultBaseUrl = "https://github.com/bell-sw",
        defaultPattern = "[module]/releases/download/[revision]/[artifact][revision]-[classifier].[ext]",
        versionFormat = WITH_BUILD,
        exampleVersion = "21.0.13+11",
    ) {
        override fun artifact(
            version: JdkVersion,
            target: Target,
        ) = archive(module = "Liberica", version, "bellsoft-jdk", classifier(target, mac = "macos", x64 = "amd64"), target)
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
        id = "microsoft",
        vendorSpec = JvmVendorSpec.MICROSOFT,
        defaultBaseUrl = "https://aka.ms/download-jdk",
        defaultPattern = "[artifact]-[revision]-[classifier].[ext]",
        versionFormat = PLAIN,
        exampleVersion = "21.0.12",
    ) {
        override fun artifact(
            version: JdkVersion,
            target: Target,
        ) = archive(module = "microsoft-jdk", version, "microsoft-jdk", classifier(target, mac = "macos"), target)
    },

    /**
     * SAP's SapMachine, published as GitHub release assets in Oracle's file-name style:
     * `.../SapMachine/releases/download/sapmachine-21.0.13/sapmachine-jdk-21.0.13_macos-aarch64_bin.tar.gz`
     *
     * SapMachine 11 is out of support and has no current builds, so the catalogue has no pin for it.
     */
    SAPMACHINE(
        id = "sapmachine",
        vendorSpec = JvmVendorSpec.SAP,
        defaultBaseUrl = "https://github.com/SAP",
        defaultPattern = "[module]/releases/download/sapmachine-[revision]/[artifact]-[revision]_[classifier]_bin.[ext]",
        versionFormat = PLAIN,
        exampleVersion = "21.0.13",
    ) {
        override fun artifact(
            version: JdkVersion,
            target: Target,
        ) = archive(module = "SapMachine", version, "sapmachine-jdk", classifier(target, mac = "macos"), target)
    },
    ;

    /** Ivy organisation, and the top-level directory of the curated repository. */
    val group: String get() = id

    /** How one archive of this distribution is addressed as an Ivy module plus explicit artifact. */
    abstract fun artifact(
        version: JdkVersion,
        target: Target,
    ): RuntimeArtifact

    /** Rejects a pin that this distribution could never publish, before any download is attempted. */
    fun requireVersion(version: JdkVersion) {
        if (!versionFormat.matches(version.value)) {
            throw GradleException(
                "'$version' is not a $id version. $id versions look like $exampleVersion, and the whole " +
                    "version is part of the download URL.",
            )
        }
    }

    /** The common case: the version is the revision as-is and the target goes in the classifier. */
    protected fun archive(
        module: String,
        version: JdkVersion,
        artifact: String,
        classifier: String,
        target: Target,
    ) = RuntimeArtifact(
        group = group,
        module = module,
        revision = version.value,
        artifact = artifact,
        classifier = classifier,
        extension = target.archiveExtension,
    )

    /** `<os><separator><arch>` in one vendor's spelling; the defaults match no vendor's macOS. */
    protected fun classifier(
        target: Target,
        mac: String,
        windows: String = "windows",
        x64: String = "x64",
        separator: String = "-",
    ): String {
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
        val DEFAULT = TEMURIN

        fun parse(value: String): JdkDistribution =
            entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) }
                ?: throw GradleException(
                    "Unsupported JDK distribution '$value'. Supported: ${entries.joinToString { it.id }}. " +
                        "For any other vendor, keep one of these and override " +
                        "javaApp.jdk.url and javaApp.jdk.pattern.",
                )
    }
}

/** OpenJDK's own `feature.interim.update.patch` with no build number: Oracle, Microsoft, SAP. */
private val PLAIN = Regex("""\d+(\.\d+)*""")

/** The same plus a `+build` that the vendor puts in its URLs: Temurin, Liberica. */
private val WITH_BUILD = Regex("""\d+(\.\d+)*\+\d+""")
