package io.github.fiftieshousewife.detektrules

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceLinesTest {
    @Test
    fun `counts non-blank lines and the comment lines among them`() {
        val text = "package a\n\n// why\nval x = 1 // trailing\n/**\n * KDoc\n */\nfun f() = 2\n/* one */\n"
        assertEquals(SourceLines(nonBlank = 8, comments = 5), SourceLines.of(text))
    }

    @Test
    fun `a share of an empty file is zero, and otherwise comments over non-blank lines`() {
        assertAll(
            { assertEquals(0.0, SourceLines(0, 0).commentShare) },
            { assertEquals(0.25, SourceLines(8, 2).commentShare) },
        )
    }
}
