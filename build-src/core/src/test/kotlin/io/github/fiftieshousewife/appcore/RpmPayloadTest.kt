package io.github.fiftieshousewife.appcore

import com.github.luben.zstd.ZstdOutputStream
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.redline_rpm.Builder
import org.redline_rpm.header.Architecture
import org.redline_rpm.header.Os
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class RpmPayloadTest {
    @TempDir
    lateinit var tmp: File

    /** A JDK-shaped package installed where vendors put theirs, with links of both kinds. */
    private fun jdkRpm(): File {
        val home = "/usr/lib/jvm/jdk-21.0.5-oracle-x64"
        val release = tmp.resolve("release").apply { writeText("JAVA_VERSION=\"21.0.5\"\n") }
        val java = tmp.resolve("java").apply { writeText("#!/bin/sh\n") }
        val built = Builder().apply {
            setPackage("jdk-21", "21.0.5", "1")
            setPlatform(Architecture.X86_64, Os.LINUX)
            addFile("$home/release", release, OCTAL_644)
            addFile("$home/bin/java", java, OCTAL_755)
            addLink("$home/legal/LICENSE", "../release")
            addLink("$home/bin/java-absolute", "$home/bin/java")
            addLink("$home/bin/java-chained", "java-absolute")
            addLink("$home/conf", "bin")
            addLink("$home/lib/security/cacerts", "/etc/pki/java/cacerts")
            addLink("$home/loop-a", "loop-b")
            addLink("$home/loop-b", "loop-a")
        }.build(tmp)
        return tmp.resolve(built)
    }

    @Test
    fun `a JDK's home is found wherever the package installs it, with its modes`() {
        val unpacked = tmp.resolve("unpacked")
        RpmPayload.unpack(jdkRpm(), unpacked)
        val home = RpmPayload.home(unpacked, "release", "jdk.rpm")
        assertAll(
            { assertEquals("jdk-21.0.5-oracle-x64", home.name) },
            { assertTrue(home.resolve("bin/java").canExecute()) },
        )
    }

    /** Windows can't create links without privileges, so every link is written as what it points to. */
    @Test
    fun `links become copies of their targets, relative, absolute, chained or to a directory`() {
        val unpacked = tmp.resolve("unpacked")
        RpmPayload.unpack(jdkRpm(), unpacked)
        val home = RpmPayload.home(unpacked, "release", "jdk.rpm")
        val copies = listOf("legal/LICENSE", "bin/java-absolute", "bin/java-chained", "conf/java").map(home::resolve)
        assertAll(
            { assertTrue(copies.none { Files.isSymbolicLink(it.toPath()) }, copies.toString()) },
            { assertEquals("JAVA_VERSION=\"21.0.5\"\n", home.resolve("legal/LICENSE").readText()) },
            { assertEquals("#!/bin/sh\n", home.resolve("bin/java-absolute").readText()) },
            { assertEquals("#!/bin/sh\n", home.resolve("bin/java-chained").readText()) },
            { assertTrue(home.resolve("bin/java-chained").canExecute()) },
            { assertTrue(home.resolve("conf").isDirectory && home.resolve("conf/java").isFile) },
        )
    }

    @Test
    fun `links outside the package, or in a loop, are left out and reported`() {
        val unpacked = tmp.resolve("unpacked")
        val skipped = RpmPayload.unpack(jdkRpm(), unpacked)
        val home = RpmPayload.home(unpacked, "release", "jdk.rpm")
        assertAll(
            { assertTrue(!home.resolve("lib/security/cacerts").exists()) },
            { assertEquals(3, skipped.size, skipped.toString()) },
            { assertTrue(skipped.any { it.endsWith("cacerts -> /etc/pki/java/cacerts") }, skipped.toString()) },
        )
    }

    @Test
    fun `a package without a runtime home is refused`() {
        val unpacked = tmp.resolve("unpacked")
        RpmPayload.unpack(jdkRpm(), unpacked)
        assertThrows(GradleException::class.java) { RpmPayload.home(unpacked, "node", "jdk.rpm") }
    }

    @Test
    fun `zstd payloads, the RHEL 9 default, are decompressed`() {
        val compressed = ByteArrayOutputStream().also { out ->
            ZstdOutputStream(out).use { it.write("payload".toByteArray()) }
        }
        val payload = RpmPayload.decompressed("zstd", ByteArrayInputStream(compressed.toByteArray()))
        assertEquals("payload", payload.readBytes().decodeToString())
    }

    @Test
    fun `an unknown payload compressor is refused by name`() {
        assertThrows(GradleException::class.java) {
            RpmPayload.decompressed("brotli", ByteArrayInputStream(ByteArray(0)))
        }
    }

    private companion object {
        const val OCTAL_644 = 420
        const val OCTAL_755 = 493
    }
}
