package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.GradleException
import org.gradle.jvm.toolchain.JvmVendorSpec

/**
 * A JDK vendor and its download layout. Both the compile toolchain and the embedded runtime come
 * from the one chosen (`javaApp.jdk.distribution`), so they can't disagree; `javaApp.jdk.url` and
 * `javaApp.jdk.pattern` reach a layout this enum doesn't know without a plugin release.
 *
 * Oracle's licence, not this plugin, decides whether an archive with an Oracle runtime may be
 * redistributed: NFTC releases allow it, OTN-licensed ones don't.
 */
enum class JdkDistribution(
    val id: String,
    val vendorSpec: JvmVendorSpec,
    val site: JdkSite,
    val versionForm: VersionForm,
) {
    /** `temurin21-binaries/releases/download/jdk-21.0.13+9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz` */
    TEMURIN(
        "temurin",
        JvmVendorSpec.ADOPTIUM,
        JdkSite("https://github.com/adoptium", "[module]/releases/download/[revision]/[artifact].[ext]"),
        VersionForm(VersionForm.WITH_BUILD, "21.0.13+9"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) = adoptiumAsset(version, target, "jdk")

        // Temurin 24+ is built with JEP 493 and ships jmods/, which a cross link needs, as a separate archive.
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

    /** `java/25/archive/jdk-25.0.2_linux-x64_bin.tar.gz` */
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

    /** Amazon adds its own build and revision: `21.0.13.0.10/amazon-corretto-21.0.13.0.10-macosx-aarch64.tar.gz` */
    CORRETTO(
        "corretto",
        JvmVendorSpec.AMAZON,
        JdkSite("https://corretto.aws/downloads/resources", "[revision]/[artifact]-[revision]-[classifier].[ext]"),
        VersionForm(Regex("""\d+(\.\d+){4}"""), "21.0.13.0.10"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) = archive(
            ArchiveName("corretto-${version.feature}", "amazon-corretto", Spelling.CORRETTO, windowsSuffix = "-jdk"),
            version,
            target,
        )
    },

    /**
     * Only Azul's version and the OpenJDK one together identify a build, so the pin is both:
     * `bin/zulu21.54.107-ca-jdk21.0.13-macosx_aarch64.tar.gz`. `ca` is the free community build.
     */
    ZULU(
        "zulu",
        JvmVendorSpec.AZUL,
        JdkSite("https://cdn.azul.com/zulu", "bin/[artifact][revision]-[classifier].[ext]"),
        VersionForm(Regex("""\d+(\.\d+)+-ca-jdk\d+(\.\d+)*"""), "21.54.107-ca-jdk21.0.13"),
    ) {
        override fun artifact(version: JdkVersion, target: Target) =
            archive(ArchiveName("zulu${version.feature}", "zulu", Spelling.ZULU), version, target)
    },

    /** The standard JDK, not Lite or Full: `21.0.13+11/bellsoft-jdk21.0.13+11-macos-aarch64.tar.gz` */
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
            archive(ArchiveName("Liberica", "bellsoft-jdk", Spelling.LIBERICA), version, target)
    },

    /** aka.ms answers an unreleased version with a web page, not a 404: a mistyped pin fails at extraction. */
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

    /** `sapmachine-21.0.13/sapmachine-jdk-21.0.13_macos-aarch64_bin.tar.gz` */
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

    val group: String get() = id

    /** A YUM repository's package name, where the vendor fixes one: Oracle's `jdk-21`. */
    val yumPackage: String? get() = if (this == ORACLE) "jdk-[feature]" else null

    /** The `IMPLEMENTOR` this distribution's JDKs write in their `release` file. */
    val implementor: String get() = when (this) {
        TEMURIN -> "Eclipse Adoptium"
        ORACLE -> "Oracle Corporation"
        CORRETTO -> "Amazon.com Inc."
        ZULU -> "Azul Systems, Inc."
        LIBERICA -> "BellSoft"
        MICROSOFT -> "Microsoft"
        SAPMACHINE -> "SAP SE"
    }

    /** The product's name on endoflife.date, for `checkRuntimeSupport`. */
    val supportProduct: String get() = when (this) {
        TEMURIN -> "eclipse-temurin"
        ORACLE -> "oracle-jdk"
        CORRETTO -> "amazon-corretto"
        ZULU -> "azul-zulu"
        LIBERICA -> "bellsoft-liberica"
        MICROSOFT -> "microsoft-build-of-openjdk"
        SAPMACHINE -> "sapmachine"
    }

    abstract fun artifact(version: JdkVersion, target: Target): RuntimeArtifact

    /** Rejects a pin this vendor could never publish, before any download: the version is in the URL. */
    fun requireVersion(version: JdkVersion) {
        if (!versionForm.regex.matches(version.value)) {
            throw GradleException(
                "'$version' is not a $id version. $id versions look like ${versionForm.example}, and the whole " +
                    "version is part of the download URL.",
            )
        }
    }

    /** Which feature releases the vendor builds, when not every one; shown when a pin is missing. */
    open val coverage: String = ""

    open fun separateJmods(feature: Int): Boolean = false

    open fun jmodsArtifact(version: JdkVersion, target: Target): RuntimeArtifact =
        throw GradleException("$id publishes jmods/ inside its JDK archives")

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

        private const val JEP_493_RELEASE = 24

        fun parse(value: String): JdkDistribution =
            entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) }
                ?: throw GradleException(
                    "Unsupported JDK distribution '$value'. Supported: ${entries.joinToString { it.id }}. " +
                        "For any other vendor, keep one of these and override javaApp.jdk.url and javaApp.jdk.pattern.",
                )
    }
}
