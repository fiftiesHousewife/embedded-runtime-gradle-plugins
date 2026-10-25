package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.platform.Architecture
import org.gradle.platform.OperatingSystem
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI

class ToolchainRequestsTest {
    @TempDir
    lateinit var dir: File

    private val download = URI("https://example.com/jdk.tar.gz")

    @Test
    fun `maps Gradle's build platform onto a target, or none for one it cannot serve`() {
        assertAll(
            {
                assertEquals(
                    Target.MAC_AARCH64,
                    ToolchainRequests.targetOf(OperatingSystem.MAC_OS, Architecture.AARCH64),
                )
            },
            {
                assertEquals(
                    Target.WINDOWS_X64,
                    ToolchainRequests.targetOf(OperatingSystem.WINDOWS, Architecture.X86_64),
                )
            },
            { assertNull(ToolchainRequests.targetOf(OperatingSystem.FREE_BSD, Architecture.X86_64)) },
            { assertNull(ToolchainRequests.targetOf(OperatingSystem.LINUX, Architecture.X86)) },
        )
    }

    @Test
    fun `serves only the configured vendor's HotSpot builds`() {
        assertAll(
            {
                assertTrue(
                    ToolchainRequests.serves(
                        JdkDistribution.TEMURIN,
                        JvmVendorSpec.ADOPTIUM,
                        JvmImplementation.VENDOR_SPECIFIC,
                    ),
                )
            },
            {
                assertFalse(
                    ToolchainRequests.serves(
                        JdkDistribution.TEMURIN,
                        JvmVendorSpec.AMAZON,
                        JvmImplementation.VENDOR_SPECIFIC,
                    ),
                )
            },
            {
                assertFalse(
                    ToolchainRequests.serves(JdkDistribution.TEMURIN, JvmVendorSpec.ADOPTIUM, JvmImplementation.J9),
                )
            },
        )
    }

    @Test
    fun `a JDK archive configured by path wins, and an installed directory is left to Gradle`() {
        val archive = File(dir, "jdk.tar.gz").apply { writeText("") }
        assertAll(
            { assertEquals(download, ToolchainRequests.uri(null) { download }) },
            { assertEquals(archive.toURI(), ToolchainRequests.uri(archive) { download }) },
            { assertNull(ToolchainRequests.uri(dir) { download }) },
        )
    }
}
