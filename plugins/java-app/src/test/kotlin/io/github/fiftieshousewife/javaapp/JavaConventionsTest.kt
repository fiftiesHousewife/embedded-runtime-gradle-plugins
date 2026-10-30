package io.github.fiftieshousewife.javaapp

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JavaConventionsTest {
    @Test
    fun `strips what a server never needs, with no native tools, and compresses with the release's own option`() {
        val stripped = listOf("--strip-java-debug-attributes", "--no-man-pages", "--no-header-files")
        assertAll(
            { assertEquals(stripped + "--compress=2", JavaConventions.jlinkOptions(17)) },
            { assertEquals(stripped + "--compress=zip-6", JavaConventions.jlinkOptions(21)) },
        )
    }
}
