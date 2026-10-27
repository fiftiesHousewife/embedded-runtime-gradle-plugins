package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TargetTest {
    @Test
    fun `parses a target id in any case and with surrounding space`() {
        assertAll(
            { assertEquals(Target.LINUX_X64, Target.parse("linux-x64")) },
            { assertEquals(Target.MAC_AARCH64, Target.parse(" MAC-AARCH64 ")) },
        )
    }

    @Test
    fun `rejects a target it does not support`() {
        assertThrows(GradleException::class.java) { Target.parse("linux-ppc64le") }
    }

    @Test
    fun `parses a comma-separated list, ignoring blanks`() {
        assertEquals(listOf(Target.LINUX_X64, Target.WINDOWS_X64), Target.parseList("linux-x64,,windows-x64"))
    }

    @Test
    fun `names per-target tasks and archives consistently`() {
        assertAll(
            { assertEquals("LinuxX64", Target.LINUX_X64.taskSuffix) },
            { assertEquals("MacAarch64", Target.MAC_AARCH64.taskSuffix) },
            { assertEquals("zip", Target.WINDOWS_X64.archiveExtension) },
            { assertEquals("tar.gz", Target.LINUX_AARCH64.archiveExtension) },
        )
    }

    @Test
    fun `counts every target but this machine's as untested`() {
        val targets = listOf(Target.LINUX_X64, Target.MAC_AARCH64)
        assertAll(
            {
                assertEquals(
                    listOf(Target.LINUX_X64),
                    untestedTargets(targets, Target.MAC_AARCH64, smokeTestEnabled = true),
                )
            },
            { assertEquals(targets, untestedTargets(targets, Target.MAC_AARCH64, smokeTestEnabled = false)) },
        )
    }
}
