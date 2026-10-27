package io.github.fiftieshousewife.javaapp.tasks

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

class PinnedDownloadTest {
    @TempDir
    lateinit var dir: File

    private val certificate =
        File(PinnedDownloadTest::class.java.getResource("/certificates/acme-root.pem")!!.toURI())
    private val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.readBytes()))
    private val download = PinnedDownload { null }

    @Test
    fun `a certificate whose SHA-256 matches the pin is fetched`() {
        val fetched = download.fetch(certificate.toURI(), sha256, dir)
        assertArrayEquals(certificate.readBytes(), fetched.readBytes())
    }

    @Test
    fun `a certificate whose content changed is refused rather than trusted`() {
        val failure =
            assertThrows(GradleException::class.java) { download.fetch(certificate.toURI(), "0".repeat(64), dir) }
        assertTrue(failure.message!!.contains("refusing to trust it"), failure.message)
    }
}
