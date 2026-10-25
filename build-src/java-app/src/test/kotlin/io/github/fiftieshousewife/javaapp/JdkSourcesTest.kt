package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Artifactory
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The download URL is the one thing in this plugin that cannot be checked by a functional test
 * without reaching the real internet, and it is also the thing most likely to be broken by a
 * refactor: every vendor spells the OS and CPU differently. These assertions are the real URLs,
 * verified by hand against each vendor's site.
 */
class JdkSourcesTest {
    private fun public(
        distribution: JdkDistribution,
        jdkUrl: String? = null,
        jdkPattern: String? = null,
    ) = JdkSources(distribution, JdkLocation.Public(jdkUrl, jdkPattern))

    private fun uri(
        sources: JdkSources,
        version: String,
        target: Target,
    ) = sources.downloadUri(JdkVersion(version), target).toString()

    @Test
    fun `temurin urls match adoptium's github release assets`() {
        val sources = public(JdkDistribution.TEMURIN)
        // The '+' in the tag has to be percent-escaped or GitHub reads it as a space.
        assertEquals(
            "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/" +
                "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz",
            uri(sources, "21.0.13+9", Target.LINUX_X64),
        )
        assertEquals(
            "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/" +
                "OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz",
            uri(sources, "25.0.5+8", Target.MAC_AARCH64),
        )
        // Windows is the one target that ships a zip rather than a tarball.
        assertEquals(
            "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/" +
                "OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip",
            uri(sources, "17.0.21+9", Target.WINDOWS_X64),
        )
    }

    @Test
    fun `oracle urls match the oracle archive, which spells macos differently`() {
        val sources = public(JdkDistribution.ORACLE)
        assertEquals(
            "https://download.oracle.com/java/25/archive/jdk-25.0.3_linux-x64_bin.tar.gz",
            uri(sources, "25.0.3", Target.LINUX_X64),
        )
        assertEquals(
            "https://download.oracle.com/java/25/archive/jdk-25.0.3_linux-aarch64_bin.tar.gz",
            uri(sources, "25.0.3", Target.LINUX_AARCH64),
        )
        // Temurin calls this 'mac', Oracle calls it 'macos'.
        assertEquals(
            "https://download.oracle.com/java/25/archive/jdk-25.0.3_macos-aarch64_bin.tar.gz",
            uri(sources, "25.0.3", Target.MAC_AARCH64),
        )
        assertEquals(
            "https://download.oracle.com/java/21/archive/jdk-21.0.12_windows-x64_bin.zip",
            uri(sources, "21.0.12", Target.WINDOWS_X64),
        )
    }

    @Test
    fun `corretto urls match amazon's cdn, which suffixes the windows zip`() {
        val sources = public(JdkDistribution.CORRETTO)
        assertEquals(
            "https://corretto.aws/downloads/resources/21.0.13.0.10/amazon-corretto-21.0.13.0.10-linux-aarch64.tar.gz",
            uri(sources, "21.0.13.0.10", Target.LINUX_AARCH64),
        )
        assertEquals(
            "https://corretto.aws/downloads/resources/21.0.13.0.10/amazon-corretto-21.0.13.0.10-macosx-aarch64.tar.gz",
            uri(sources, "21.0.13.0.10", Target.MAC_AARCH64),
        )
        assertEquals(
            "https://corretto.aws/downloads/resources/21.0.13.0.10/amazon-corretto-21.0.13.0.10-windows-x64-jdk.zip",
            uri(sources, "21.0.13.0.10", Target.WINDOWS_X64),
        )
    }

    @Test
    fun `zulu urls match azul's cdn, which uses underscores and win`() {
        val sources = public(JdkDistribution.ZULU)
        assertEquals(
            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-linux_x64.tar.gz",
            uri(sources, "21.54.107-ca-jdk21.0.13", Target.LINUX_X64),
        )
        assertEquals(
            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-macosx_aarch64.tar.gz",
            uri(sources, "21.54.107-ca-jdk21.0.13", Target.MAC_AARCH64),
        )
        assertEquals(
            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-win_x64.zip",
            uri(sources, "21.54.107-ca-jdk21.0.13", Target.WINDOWS_X64),
        )
    }

    @Test
    fun `liberica urls match bellsoft's github release assets, which say amd64`() {
        val sources = public(JdkDistribution.LIBERICA)
        assertEquals(
            "https://github.com/bell-sw/Liberica/releases/download/21.0.13%2B11/bellsoft-jdk21.0.13%2B11-linux-amd64.tar.gz",
            uri(sources, "21.0.13+11", Target.LINUX_X64),
        )
        assertEquals(
            "https://github.com/bell-sw/Liberica/releases/download/21.0.13%2B11/bellsoft-jdk21.0.13%2B11-macos-aarch64.tar.gz",
            uri(sources, "21.0.13+11", Target.MAC_AARCH64),
        )
        assertEquals(
            "https://github.com/bell-sw/Liberica/releases/download/21.0.13%2B11/bellsoft-jdk21.0.13%2B11-windows-amd64.zip",
            uri(sources, "21.0.13+11", Target.WINDOWS_X64),
        )
    }

    @Test
    fun `microsoft urls match the aka-ms redirector`() {
        val sources = public(JdkDistribution.MICROSOFT)
        assertEquals(
            "https://aka.ms/download-jdk/microsoft-jdk-21.0.12-linux-aarch64.tar.gz",
            uri(sources, "21.0.12", Target.LINUX_AARCH64),
        )
        assertEquals(
            "https://aka.ms/download-jdk/microsoft-jdk-21.0.12-macos-x64.tar.gz",
            uri(sources, "21.0.12", Target.MAC_X64),
        )
        assertEquals(
            "https://aka.ms/download-jdk/microsoft-jdk-21.0.12-windows-x64.zip",
            uri(sources, "21.0.12", Target.WINDOWS_X64),
        )
    }

    @Test
    fun `sapmachine urls match sap's github release assets`() {
        val sources = public(JdkDistribution.SAPMACHINE)
        assertEquals(
            "https://github.com/SAP/SapMachine/releases/download/sapmachine-21.0.13/sapmachine-jdk-21.0.13_linux-x64_bin.tar.gz",
            uri(sources, "21.0.13", Target.LINUX_X64),
        )
        assertEquals(
            "https://github.com/SAP/SapMachine/releases/download/sapmachine-21.0.13/sapmachine-jdk-21.0.13_macos-aarch64_bin.tar.gz",
            uri(sources, "21.0.13", Target.MAC_AARCH64),
        )
        assertEquals(
            "https://github.com/SAP/SapMachine/releases/download/sapmachine-21.0.13/sapmachine-jdk-21.0.13_windows-x64_bin.zip",
            uri(sources, "21.0.13", Target.WINDOWS_X64),
        )
    }

    @Test
    fun `a url and pattern override reaches a layout no distribution knows about`() {
        // Oracle populates [classifier] with its own os-arch spelling, so a mirror can be addressed
        // by target alone.
        val oracle =
            public(
                JdkDistribution.ORACLE,
                jdkUrl = "https://mirror.example.com/jdks",
                jdkPattern = "[organisation]/[revision]/[classifier]/jdk.[ext]",
            )
        assertEquals(
            "https://mirror.example.com/jdks/oracle/25.0.3/linux-aarch64/jdk.tar.gz",
            uri(oracle, "25.0.3", Target.LINUX_AARCH64),
        )

        // Temurin encodes the platform in [artifact] and leaves [classifier] empty, so a pattern
        // for it must use [artifact]; an empty [classifier] collapses out of the path.
        val temurin =
            public(
                JdkDistribution.TEMURIN,
                jdkUrl = "https://mirror.example.com/jdks",
                jdkPattern = "[organisation]/[revision]/[artifact].[ext]",
            )
        assertEquals(
            "https://mirror.example.com/jdks/temurin/jdk-21.0.13%2B9/" +
                "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz",
            uri(temurin, "21.0.13+9", Target.LINUX_X64),
        )
    }

    @Test
    fun `the curated artifactory layout is vendor-independent and overridable`() {
        fun artifactory(jdkPattern: String?) =
            JdkSources(
                JdkDistribution.ORACLE,
                JdkLocation.artifactory(
                    Artifactory(
                        baseUrl = "https://artifactory.example.com/artifactory",
                        mavenRepo = "maven-virtual",
                        gradlePluginsRepo = "gradle-plugins-virtual",
                        runtimesRepo = "runtimes",
                        releasesRepo = "libs-release-local",
                        snapshotsRepo = "libs-snapshot-local",
                        allowInsecureProtocol = false,
                    ),
                    jdkPattern,
                ),
            )

        assertEquals(
            "https://artifactory.example.com/artifactory/runtimes/oracle/jdk/25.0.3/jdk-25.0.3-linux-x64.tar.gz",
            uri(artifactory(null), "25.0.3", Target.LINUX_X64),
        )
        assertEquals(
            "https://artifactory.example.com/artifactory/runtimes/jdk/25.0.3-linux-x64.tar.gz",
            uri(artifactory("[module]/[revision]-[classifier].[ext]"), "25.0.3", Target.LINUX_X64),
        )
    }

    @Test
    fun `each distribution rejects a version its own layout cannot address`() {
        val rejected =
            mapOf(
                JdkDistribution.TEMURIN to "21.0.13",
                JdkDistribution.ORACLE to "25.0.3+9",
                JdkDistribution.CORRETTO to "21.0.13",
                JdkDistribution.ZULU to "21.0.13",
                JdkDistribution.LIBERICA to "21.0.13",
                JdkDistribution.MICROSOFT to "21.0.12+7",
                JdkDistribution.SAPMACHINE to "21.0.13+9",
            )
        assertEquals(JdkDistribution.entries.toSet(), rejected.keys, "every distribution needs a rejected example")
        rejected.forEach { (distribution, version) ->
            assertThrows(GradleException::class.java, { distribution.requireVersion(JdkVersion(version)) }, distribution.id)
            distribution.requireVersion(JdkVersion(distribution.exampleVersion))
        }
    }

    @Test
    fun `every catalogue pin is in its own distribution's version form`() {
        JdkDistribution.entries.forEach { distribution ->
            JdkCatalog.SUPPORTED_FEATURES.forEach { feature ->
                JdkCatalog.cataloguedJdk(distribution, feature)?.let { pin ->
                    val version = JdkVersion(pin)
                    distribution.requireVersion(version)
                    assertEquals(feature, version.feature, "jdk.${distribution.id}.$feature=$pin")
                }
            }
        }
    }
}
