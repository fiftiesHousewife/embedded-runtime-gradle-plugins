package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class RuntimeRepositoryTest {
    private val curated =
        RuntimeRepository.curated(
            "curated",
            URI("https://repo.example.com/runtimes/"),
            RuntimeRepository.CURATED_PATTERN,
            "temurin",
        )

    @Test
    fun `fills every Ivy placeholder and escapes the plus of a build number`() {
        val artifact = curated.curated("jdk", "21.0.12.1+1", Target.LINUX_X64)
        assertEquals(
            "https://repo.example.com/runtimes/temurin/jdk/21.0.12.1%2B1/jdk-21.0.12.1%2B1-linux-x64.tar.gz",
            curated.downloadUri(artifact).toString(),
        )
    }

    @Test
    fun `names a curated archive after its module and target`() {
        val node = RuntimeRepository.curated("node", URI("https://r/"), RuntimeRepository.CURATED_PATTERN, "nodejs")
        val artifact = node.curated("node", "24.22.1", Target.WINDOWS_X64)
        assertAll(
            { assertEquals("nodejs:node:24.22.1", artifact.notation) },
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

    @Test
    fun `an optional segment appears only when every token in it has a value, as in Ivy`() {
        val optional =
            RuntimeRepository(
                "mirror",
                URI("https://mirror.example.com/"),
                "[module]/[artifact](-[classifier]).[ext]",
                "g",
            )
        val withClassifier = RuntimeArtifact("g", "jdk", "21", "jdk", "linux-x64", "tar.gz")
        val without = withClassifier.copy(classifier = null)
        assertAll(
            {
                assertEquals(
                    "https://mirror.example.com/jdk/jdk-linux-x64.tar.gz",
                    optional.downloadUri(withClassifier).toString(),
                )
            },
            { assertEquals("https://mirror.example.com/jdk/jdk.tar.gz", optional.downloadUri(without).toString()) },
        )
    }
}
