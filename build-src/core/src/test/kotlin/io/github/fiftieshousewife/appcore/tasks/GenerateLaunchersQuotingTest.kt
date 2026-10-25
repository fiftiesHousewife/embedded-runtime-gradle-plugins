package io.github.fiftieshousewife.appcore.tasks

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenerateLaunchersQuotingTest {
    @Test
    fun `quotes a value as one POSIX shell word, single quotes included`() {
        assertAll(
            { assertEquals("'-Xmx1g'", GenerateLaunchers.shQuote("-Xmx1g")) },
            { assertEquals("'it'\"'\"'s'", GenerateLaunchers.shQuote("it's")) },
        )
    }

    @Test
    fun `quotes a value as one batch argument, double quotes doubled`() {
        assertEquals("\"say \"\"hi\"\"\"", GenerateLaunchers.cmdQuote("say \"hi\""))
    }
}
