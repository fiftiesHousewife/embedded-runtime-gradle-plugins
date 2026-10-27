package io.github.fiftieshousewife.appcore.tasks

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UntestedArchivesTest {
    @Test
    fun `describes one archive or several in agreeing grammar`() {
        assertAll(
            { assertEquals("the linux-x64 archive was", UntestedArchives.describe(listOf("linux-x64"))) },
            {
                assertEquals(
                    "the linux-x64, windows-x64 archives were",
                    UntestedArchives.describe(listOf("linux-x64", "windows-x64")),
                )
            },
        )
    }

    @Test
    fun `a refusal names the property that overrides it`() {
        val refusal = UntestedArchives.refusal(listOf("linux-aarch64"), "javaApp.allowUntestedPublish")
        assertAll(
            {
                assertTrue(
                    refusal.startsWith("Refusing to publish: the linux-aarch64 archive was never started"),
                    refusal,
                )
            },
            { assertTrue(refusal.contains("-PjavaApp.allowUntestedPublish=true"), refusal) },
        )
    }
}
