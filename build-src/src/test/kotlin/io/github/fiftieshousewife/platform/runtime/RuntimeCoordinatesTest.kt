package io.github.fiftieshousewife.platform.runtime

import io.github.fiftieshousewife.platform.ArtifactoryLayout
import io.github.fiftieshousewife.platform.PlatformRepositories
import io.github.fiftieshousewife.platform.PublicSources
import io.github.fiftieshousewife.platform.RepositoryMode
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The download URL is the one thing in this plugin that cannot be checked by a functional test
 * without reaching the real internet, and it is also the thing most likely to be broken by a
 * refactor: every vendor spells the platform differently. These assertions are the real URLs,
 * verified by hand against each vendor's site.
 */
class RuntimeCoordinatesTest {
    private fun public(
        distribution: JdkDistribution,
        jdkUrl: String? = null,
        jdkPattern: String? = null,
    ) = PlatformRepositories(
        mode = RepositoryMode.PUBLIC,
        jdkDistribution = distribution,
        artifactory = null,
        public =
            PublicSources(
                jdkUrl = jdkUrl,
                jdkPattern = jdkPattern,
                nodeUrl = "https://nodejs.org/dist",
                nodePattern = null,
                npmRegistry = "https://registry.npmjs.org/",
            ),
    )

    private fun uri(
        repos: PlatformRepositories,
        version: String,
        platform: Platform,
    ) = RuntimeCoordinates
        .downloadUri(
            repos,
            RuntimeCoordinates.jdk(repos, JdkVersion(version), platform),
        ).toString()

    @Test
    fun `temurin urls match adoptium's github release assets`() {
        val repos = public(JdkDistribution.TEMURIN)
        // The '+' in the tag has to be percent-escaped or GitHub reads it as a space.
        assertEquals(
            "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/" +
                "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz",
            uri(repos, "21.0.13+9", Platform.LINUX_X64),
        )
        assertEquals(
            "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/" +
                "OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz",
            uri(repos, "25.0.5+8", Platform.MAC_AARCH64),
        )
        // Windows is the one target that ships a zip rather than a tarball.
        assertEquals(
            "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/" +
                "OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip",
            uri(repos, "17.0.21+9", Platform.WINDOWS_X64),
        )
    }

    @Test
    fun `oracle urls match the oracle archive, which spells macos differently`() {
        val repos = public(JdkDistribution.ORACLE)
        assertEquals(
            "https://download.oracle.com/java/25/archive/jdk-25.0.3_linux-x64_bin.tar.gz",
            uri(repos, "25.0.3", Platform.LINUX_X64),
        )
        assertEquals(
            "https://download.oracle.com/java/25/archive/jdk-25.0.3_linux-aarch64_bin.tar.gz",
            uri(repos, "25.0.3", Platform.LINUX_AARCH64),
        )
        // Temurin calls this 'mac', Oracle calls it 'macos'.
        assertEquals(
            "https://download.oracle.com/java/25/archive/jdk-25.0.3_macos-aarch64_bin.tar.gz",
            uri(repos, "25.0.3", Platform.MAC_AARCH64),
        )
        assertEquals(
            "https://download.oracle.com/java/21/archive/jdk-21.0.12_windows-x64_bin.zip",
            uri(repos, "21.0.12", Platform.WINDOWS_X64),
        )
    }

    @Test
    fun `a url and pattern override reaches a layout no distribution knows about`() {
        // Oracle populates [classifier] with its own os-arch spelling, so a mirror can be addressed
        // by platform alone.
        val oracle =
            public(
                JdkDistribution.ORACLE,
                jdkUrl = "https://mirror.example.com/jdks",
                jdkPattern = "[organisation]/[revision]/[classifier]/jdk.[ext]",
            )
        assertEquals(
            "https://mirror.example.com/jdks/oracle/25.0.3/linux-aarch64/jdk.tar.gz",
            uri(oracle, "25.0.3", Platform.LINUX_AARCH64),
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
            uri(temurin, "21.0.13+9", Platform.LINUX_X64),
        )
    }

    @Test
    fun `the curated artifactory layout is vendor-independent and overridable`() {
        fun artifactory(jdkPattern: String?) =
            PlatformRepositories(
                mode = RepositoryMode.ARTIFACTORY,
                jdkDistribution = JdkDistribution.ORACLE,
                artifactory =
                    ArtifactoryLayout(
                        baseUrl = "https://artifactory.example.com/artifactory",
                        mavenRepo = "maven-virtual",
                        gradlePluginsRepo = "gradle-plugins-virtual",
                        npmRepo = "api/npm/npm-virtual",
                        runtimesRepo = "runtimes",
                        releasesRepo = "libs-release-local",
                        snapshotsRepo = "libs-snapshot-local",
                        jdkPattern = jdkPattern,
                        nodePattern = null,
                        allowInsecureProtocol = false,
                    ),
                public =
                    PublicSources(
                        jdkUrl = null,
                        jdkPattern = null,
                        nodeUrl = "https://nodejs.org/dist",
                        nodePattern = null,
                        npmRegistry = "https://registry.npmjs.org/",
                    ),
            )

        assertEquals(
            "https://artifactory.example.com/artifactory/runtimes/oracle/jdk/25.0.3/jdk-25.0.3-linux-x64.tar.gz",
            uri(artifactory(null), "25.0.3", Platform.LINUX_X64),
        )
        assertEquals(
            "https://artifactory.example.com/artifactory/runtimes/jdk/25.0.3-linux-x64.tar.gz",
            uri(artifactory("[module]/[revision]-[classifier].[ext]"), "25.0.3", Platform.LINUX_X64),
        )
    }

    @Test
    fun `each distribution rejects a version its own layout cannot address`() {
        assertThrows(GradleException::class.java) {
            JdkDistribution.TEMURIN.requireVersion(JdkVersion("21.0.13"))
        }
        assertThrows(GradleException::class.java) {
            JdkDistribution.ORACLE.requireVersion(JdkVersion("25.0.3+9"))
        }
        JdkDistribution.TEMURIN.requireVersion(JdkVersion("21.0.13+9"))
        JdkDistribution.ORACLE.requireVersion(JdkVersion("25.0.3"))
    }

    @Test
    fun `every supported platform has a node classifier`() {
        assertEquals(
            listOf("linux-x64", "linux-arm64", "win-x64", "darwin-x64", "darwin-arm64"),
            Platform.SUPPORTED.map(RuntimeCoordinates::nodeDistClassifier),
        )
    }
}
