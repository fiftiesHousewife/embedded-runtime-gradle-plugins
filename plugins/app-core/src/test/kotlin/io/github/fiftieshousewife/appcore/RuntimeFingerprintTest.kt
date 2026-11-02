package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.RuntimeFingerprint.Release
import io.github.fiftieshousewife.appcore.RuntimeFingerprint.Verdict
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeFingerprintTest {
    private val inputs = setOf("runtime=temurin 21.0.5+11", "target=linux-x64", "file jmods/java.base.jmod 0a")
    private val previous = Release("1.0.0", inputs, "aa")

    @Test
    fun `lists entries and files sorted, one per line, so the same inputs write the same bytes`() {
        val text = RuntimeFingerprint.render(
            mapOf("target" to "linux-x64", "modules" to "java.base"),
            listOf("lib/modules" to "02", "jmods/java.base.jmod" to "01"),
        )
        assertEquals(
            "file jmods/java.base.jmod 01\nfile lib/modules 02\nmodules=java.base\ntarget=linux-x64\n",
            text,
        )
    }

    @Test
    fun `tells a new target, changed inputs, a stored runtime and a duplicate apart`() {
        val changed = inputs - "runtime=temurin 21.0.5+11" + "runtime=temurin 21.0.6+7"
        assertAll(
            { assertEquals(Verdict.New, RuntimeFingerprint.compare(null, previous.copy(version = "1.0.1"))) },
            {
                assertEquals(
                    Verdict.Changed(listOf("runtime=temurin 21.0.6+7")),
                    RuntimeFingerprint.compare(previous, Release("1.0.1", changed, "bb")),
                )
            },
            { assertEquals(Verdict.Unchanged, RuntimeFingerprint.compare(previous, Release("1.0.1", inputs, "aa"))) },
            { assertEquals(Verdict.Duplicated, RuntimeFingerprint.compare(previous, Release("1.0.1", inputs, "bb"))) },
        )
    }

    @Test
    fun `a duplicate names both checksums and the previous release`() {
        val message = RuntimeFingerprint.describe("linux-x64", previous, Release("1.0.1", inputs, "bb"))
        assertAll(
            { assertTrue(message.contains("equal 1.0.0's"), message) },
            { assertTrue(message.contains("(bb against aa)"), message) },
        )
    }

    @Test
    fun `a release or file that is not there is no failure, an unreachable repository is`() {
        class ArtifactNotFoundException : RuntimeException("no file")
        val missing = IllegalStateException("Could not resolve", ArtifactNotFoundException())
        val unreachable = IllegalStateException("Could not resolve", java.io.IOException("Could not GET repo"))
        assertEquals(listOf("Could not GET repo"), RuntimeFingerprints.unreadable(listOf(missing, unreachable)))
    }
}
