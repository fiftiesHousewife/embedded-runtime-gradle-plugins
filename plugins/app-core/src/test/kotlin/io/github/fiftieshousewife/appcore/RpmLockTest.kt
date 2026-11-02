package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RpmLockTest {
    private val cdn = "https://cdn-ubi.redhat.com/content/public/ubi/dist/ubi9/9/x86_64/baseos/os"
    private val zlib = LockedRpm(
        name = "zlib",
        evr = "1.2.11-40.el9",
        url = "$cdn/Packages/z/zlib-1.2.11-40.el9.x86_64.rpm",
        sha256 = "baf95ffbf40ee014135f16fe33e343faf7ff1ca06509fd97cd988e6afeabf670",
        size = 95339,
        repoid = "ubi-9-baseos-rpms",
        sourceRpm = "zlib-1.2.11-40.el9.src.rpm",
    )
    private val lock = RpmLock(mapOf("x86_64" to listOf(zlib), "aarch64" to emptyList()))

    @Test
    fun `writes Konflux's layout and reads it back`() {
        val text = lock.render()
        assertAll(
            { assertEquals(lock, RpmLock.parse(text)) },
            {
                assertEquals(
                    listOf("---", "lockfileVersion: 1", "lockfileVendor: redhat", "arches:", "- arch: aarch64"),
                    text.lines().take(5),
                )
            },
            { assertEquals(true, text.contains("    checksum: sha256:${zlib.sha256}\n")) },
        )
    }

    @Test
    fun `names what the lock lacks for a CPU`() {
        assertAll(
            { assertEquals(listOf("libstdc++"), lock.missing(listOf("zlib", "libstdc++"), "x86_64")) },
            { assertEquals(listOf("zlib"), lock.missing(listOf("zlib"), "aarch64")) },
        )
    }

    @Test
    fun `fetches a locked file from the repository configured under its id, not the host it was locked from`() {
        val mirror = "https://artifactory.example.com/artifactory/ubi-9/x86_64/baseos/os/"
        assertAll(
            {
                assertEquals(
                    "${mirror}Packages/z/zlib-1.2.11-40.el9.x86_64.rpm",
                    zlib.urlIn(mapOf("ubi-9-baseos-rpms" to mirror)),
                )
            },
            { assertEquals(zlib.url, zlib.urlIn(mapOf("other" to mirror))) },
        )
    }
}
