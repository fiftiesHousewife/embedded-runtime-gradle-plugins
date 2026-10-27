package io.github.fiftieshousewife.javaapp

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JavaConventionsTest {
    @Test
    fun `strips what a server never needs and compresses with the release's own option`() {
        val stripped = listOf("--strip-debug", "--no-man-pages", "--no-header-files")
        assertAll(
            { assertEquals(stripped + "--compress=2", JavaConventions.jlinkOptions(17)) },
            { assertEquals(stripped + "--compress=zip-6", JavaConventions.jlinkOptions(21)) },
        )
    }
}
