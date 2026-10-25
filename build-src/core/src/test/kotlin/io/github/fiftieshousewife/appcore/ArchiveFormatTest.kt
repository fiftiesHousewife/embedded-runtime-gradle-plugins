package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class ArchiveFormatTest {
    @Test
    fun `parses each format by its extension, whatever the case`() {
        assertAll(
            { assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.parse("tar.gz")) },
            { assertEquals(ArchiveFormat.TAR, ArchiveFormat.parse(" TAR ")) },
            { assertEquals(ArchiveFormat.ZIP, ArchiveFormat.parse("zip")) },
        )
    }

    @Test
    fun `names the supported formats when given another`() {
        val failure = assertThrows<GradleException> { ArchiveFormat.parse("rar") }
        assertTrue(failure.message!!.contains("tar.gz, tar, zip"), failure.message)
    }

    @Test
    fun `tells a gzipped tar from a plain one by the file name`() {
        assertAll(
            { assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.of(File("svc-2.0.0-linux-x64.tar.gz"))) },
            { assertEquals(ArchiveFormat.TAR, ArchiveFormat.of(File("svc-2.0.0-linux-x64.tar"))) },
            { assertEquals(ArchiveFormat.ZIP, ArchiveFormat.of(File("svc-2.0.0-windows-x64.zip"))) },
        )
    }
}
