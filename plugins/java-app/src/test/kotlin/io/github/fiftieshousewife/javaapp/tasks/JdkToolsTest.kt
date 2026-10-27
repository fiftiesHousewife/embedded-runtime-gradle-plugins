package io.github.fiftieshousewife.javaapp.tasks

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JdkToolsTest {
    @TempDir
    lateinit var home: File

    private fun jdk(version: String) = home.apply { resolve("release").writeText("JAVA_VERSION=\"$version\"\n") }

    @Test
    fun `every vendor's spelling of the pinned release is accepted`() {
        listOf(
            "21.0.5" to "21.0.5",
            "21.0.5" to "21.0.5+11",
            "21.0.13" to "21.0.13.0.10",
            "21.0.13" to "21.54.107-ca-jdk21.0.13",
        )
            .forEach { (installed, pin) -> assertDoesNotThrow { JdkTools.requirePinned(pin, jdk(installed)) } }
    }

    @Test
    fun `an installed JDK a patch behind the pin is refused`() {
        assertThrows(GradleException::class.java) { JdkTools.requirePinned("21.0.5", jdk("21.0.4")) }
    }

    @Test
    fun `a shorter version that only prefixes the pin is refused`() {
        assertThrows(GradleException::class.java) { JdkTools.requirePinned("21.0.10", jdk("21.0.1")) }
    }
}
