package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpellingTest {
    @Test
    fun `writes a target in each vendor's own words`() {
        assertAll(
            { assertEquals("macos-aarch64", Spelling.MACOS.of(Target.MAC_AARCH64)) },
            { assertEquals("windows-x64", Spelling.MACOS.of(Target.WINDOWS_X64)) },
            { assertEquals("linux-amd64", Spelling(mac = "macos", x64 = "amd64").of(Target.LINUX_X64)) },
            {
                assertEquals(
                    "win_x64",
                    Spelling(mac = "macosx", windows = "win", separator = "_").of(Target.WINDOWS_X64),
                )
            },
        )
    }
}
