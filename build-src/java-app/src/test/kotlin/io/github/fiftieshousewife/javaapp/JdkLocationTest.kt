package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Artifactory
import io.github.fiftieshousewife.appcore.Target
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JdkLocationTest {
    @TempDir
    lateinit var shared: File

    private val artifactory =
        Artifactory(
            baseUrl = "https://artifactory.example.com/artifactory",
            mavenRepo = "maven-virtual",
            gradlePluginsRepo = "gradle-plugins-virtual",
            runtimesRepo = "runtimes",
            releasesRepo = "libs-release-local",
            snapshotsRepo = "libs-snapshot-local",
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
    fun `the curated Artifactory layout names every vendor's archive the same way`() {
        val curated = JdkSources(JdkDistribution.ORACLE, JdkLocation.artifactory(artifactory))
        val reshaped =
            JdkSources(
                JdkDistribution.ORACLE,
                JdkLocation.artifactory(artifactory, "[module]/[revision]-[classifier].[ext]"),
            )
        assertAll(
            {
                assertEquals(
                    "https://artifactory.example.com/artifactory/runtimes/oracle/jdk/25.0.3/" +
                        "jdk-25.0.3-linux-x64.tar.gz",
                    uri(curated, "25.0.3"),
                )
            },
            {
                assertEquals(
                    "https://artifactory.example.com/artifactory/runtimes/jdk/25.0.3-linux-x64.tar.gz",
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
}
