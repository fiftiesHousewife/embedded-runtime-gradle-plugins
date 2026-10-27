package io.github.fiftieshousewife.javaapp

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaRuntimeDefaultsTest {
    private val defaults = JavaRuntimeDefaults.Settings()

    @Test
    fun `the defaults size the heap and exit and dump on out of memory, leaving the collector to the JVM`() {
        assertEquals(
            listOf("-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-XX:+HeapDumpOnOutOfMemoryError"),
            JavaRuntimeDefaults.fixedOptions(defaults, emptyList()),
        )
    }

    @Test
    fun `a collector and attach refusal are added when asked for`() {
        val options =
            JavaRuntimeDefaults.fixedOptions(
                defaults.copy(garbageCollector = "parallel", disableAttach = true),
                emptyList(),
            )
        assertEquals(listOf("-XX:+UseParallelGC", "-XX:+DisableAttachMechanism"), options.takeLast(2))
    }

    @Test
    fun `disabled, nothing is added and nothing goes to the log directory`() {
        val off = defaults.copy(enabled = false)
        assertEquals(emptyList<String>(), JavaRuntimeDefaults.fixedOptions(off, emptyList()))
        assertTrue(!off.logDirFiles)
    }

    @Test
    fun `a collector in jvmArgs as well is refused rather than left to fail at start`() {
        val both = defaults.copy(garbageCollector = "G1")
        assertThrows(GradleException::class.java) { JavaRuntimeDefaults.fixedOptions(both, listOf("-XX:+UseSerialGC")) }
    }

    @Test
    fun `an unknown collector is refused`() {
        val unknown = defaults.copy(garbageCollector = "CMS")
        assertThrows(GradleException::class.java) { JavaRuntimeDefaults.fixedOptions(unknown, emptyList()) }
    }
}
