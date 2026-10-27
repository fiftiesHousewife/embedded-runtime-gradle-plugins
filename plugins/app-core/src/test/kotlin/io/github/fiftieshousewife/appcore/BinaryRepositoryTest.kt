package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class BinaryRepositoryTest {
    @Test
    fun `public repositories are never taken for the organisation's own`() {
        assertAll(
            { assertTrue(BinaryRepository.isPublic(URI.create("https://repo.maven.apache.org/maven2/"))) },
            { assertTrue(BinaryRepository.isPublic(URI.create("https://nodejs.org/dist/"))) },
            { assertTrue(BinaryRepository.isPublic(URI.create("https://plugins.gradle.org/m2/"))) },
        )
    }

    @Test
    fun `a host that only contains a public one's name is not public`() {
        assertAll(
            { assertFalse(BinaryRepository.isPublic(URI.create("https://artifactory.example.com/maven/"))) },
            { assertFalse(BinaryRepository.isPublic(URI.create("https://nodejs.org.example.com/dist/"))) },
        )
    }
}
