package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class RuntimeRepositoryTest {
    private val curated =
        RuntimeRepository(
            "curated",
            URI("https://repo.example.com/runtimes/"),
            RuntimeRepository.CURATED_PATTERN,
            "temurin",
        )

    @Test
    fun `fills every Ivy placeholder and escapes the plus of a build number`() {
        val artifact = RuntimeArtifact.curated("temurin", "jdk", "21.0.12.1+1", Target.LINUX_X64)
        assertEquals(
            "https://repo.example.com/runtimes/temurin/jdk/21.0.12.1%2B1/jdk-21.0.12.1%2B1-linux-x64.tar.gz",
            curated.downloadUri(artifact).toString(),
        )
    }

    @Test
    fun `names a curated archive after its module and target`() {
        val artifact = RuntimeArtifact.curated("nodejs", "node", "24.18.1", Target.WINDOWS_X64)
        assertAll(
            { assertEquals("nodejs:node:24.18.1", artifact.notation) },
            { assertEquals("node", artifact.artifact) },
            { assertEquals("windows-x64", artifact.classifier) },
            { assertEquals("zip", artifact.extension) },
        )
    }

    @Test
    fun `adds exactly one trailing slash to a base URL`() {
        assertAll(
            { assertEquals("https://example.com/a/", RuntimeRepository.slash("https://example.com/a").toString()) },
            { assertEquals("https://example.com/a/", RuntimeRepository.slash("https://example.com/a//").toString()) },
        )
    }
}
