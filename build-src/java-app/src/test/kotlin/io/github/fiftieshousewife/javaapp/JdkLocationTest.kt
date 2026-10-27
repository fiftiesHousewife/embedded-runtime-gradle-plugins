package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.CustomTokens
import io.github.fiftieshousewife.appcore.Target
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JdkLocationTest {
    @TempDir
    lateinit var shared: File

    private val binaryRepo =
        BinaryRepository(
            baseUrl = "https://repo.example.com",
            mavenRepo = "maven-virtual",
            gradlePluginsRepo = "gradle-plugins-virtual",
            runtimesRepo = "runtimes",
            releasesRepo = "libs-release-local",
            snapshotsRepo = "libs-snapshot-local",
            rpmRepo = "rpm-local",
            allowInsecureProtocol = false,
        )

    private fun uri(sources: JdkSources, version: String, target: Target = Target.LINUX_X64) =
        sources.downloadUri(JdkVersion(version), target).toString()

    private val mirror = "https://mirror.example.com/jdks"

    @Test
    fun `a mirror pattern can place each archive by its target classifier`() {
        val oracle =
            JdkSources(
                JdkDistribution.ORACLE,
                JdkLocation.Public(mirror, "[organisation]/[revision]/[classifier]/jdk.[ext]"),
            )
        assertEquals("$mirror/oracle/25.0.3/linux-aarch64/jdk.tar.gz", uri(oracle, "25.0.3", Target.LINUX_AARCH64))
    }

    @Test
    fun `a mirror pattern keeps Temurin's own artifact name, which carries the target`() {
        val temurin =
            JdkSources(
                JdkDistribution.TEMURIN,
                JdkLocation.Public(mirror, "[organisation]/[revision]/[artifact].[ext]"),
            )
        assertEquals(
            "$mirror/temurin/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz",
            uri(temurin, "21.0.13+9"),
        )
    }

    @Test
    fun `the curated binary repository layout names every vendor's archive the same way`() {
        val curated = JdkSources(JdkDistribution.ORACLE, JdkLocation.binaryRepo(binaryRepo))
        val reshaped =
            JdkSources(
                JdkDistribution.ORACLE,
                JdkLocation.binaryRepo(binaryRepo).copy(pattern = "[module]/[revision]-[classifier].[ext]"),
            )
        assertAll(
            {
                assertEquals(
                    "https://repo.example.com/runtimes/oracle/jdk/25.0.3/" +
                        "jdk-25.0.3-linux-x64.tar.gz",
                    uri(curated, "25.0.3"),
                )
            },
            {
                assertEquals(
                    "https://repo.example.com/runtimes/jdk/25.0.3-linux-x64.tar.gz",
                    uri(reshaped, "25.0.3"),
                )
            },
        )
    }

    @Test
    fun `a shared local directory is read in the curated layout, with underscores for plus`() {
        val local = JdkSources(JdkDistribution.TEMURIN, JdkLocation.local(shared))
        assertEquals(
            shared.toURI().resolve("temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz").toString(),
            uri(local, "21.0.12.1+1"),
        )
    }

    @Test
    fun `Temurin 24 and later fetch jmods beside the JDK, from the vendor or the curated tree`() {
        val version = JdkVersion("25.0.4.1+1")
        val public = JdkSources(JdkDistribution.TEMURIN)
        val curated = JdkSources(JdkDistribution.TEMURIN, JdkLocation.local(shared))
        assertAll(
            { assertTrue(JdkDistribution.TEMURIN.separateJmods(25)) },
            { assertFalse(JdkDistribution.TEMURIN.separateJmods(21)) },
            { assertFalse(JdkDistribution.CORRETTO.separateJmods(25)) },
            {
                assertEquals(
                    "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/" +
                        "OpenJDK25U-jmods_x64_linux_hotspot_25.0.4.1_1.tar.gz",
                    public.repository.downloadUri(public.jmodsArtifact(version, Target.LINUX_X64)).toString(),
                )
            },
            {
                assertEquals(
                    shared.toURI().resolve("temurin/jmods/25.0.4.1_1/jmods-25.0.4.1_1-linux-x64.tar.gz").toString(),
                    curated.repository.downloadUri(curated.jmodsArtifact(version, Target.LINUX_X64)).toString(),
                )
            },
        )
    }

    @Test
    fun `a corporate pattern names the version and platform directly, with tokens of its own`() {
        val tokens = CustomTokens(mapOf("plat" to mapOf("linux" to "l64", "windows" to "w64")))
        val sources = JdkSources(
            JdkDistribution.ORACLE,
            JdkLocation.binaryRepo(binaryRepo).copy(
                pattern = "java/[feature]/jdk-[feature].[interim].[update]_[plat]_bin.[ext]",
                customTokens = tokens,
            ),
        )
        val version = JdkVersion("21.0.5")
        assertAll(
            {
                assertEquals(
                    "https://repo.example.com/runtimes/java/21/jdk-21.0.5_l64_bin.tar.gz",
                    sources.downloadUri(version, Target.LINUX_X64).toString(),
                )
            },
            {
                assertEquals(
                    "https://repo.example.com/runtimes/java/21/jdk-21.0.5_w64_bin.zip",
                    sources.downloadUri(version, Target.WINDOWS_X64).toString(),
                )
            },
        )
    }
}
