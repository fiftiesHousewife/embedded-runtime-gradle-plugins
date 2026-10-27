package io.github.fiftieshousewife.nodeapp.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallScriptsTest {
    private val lockfile =
        mapOf(
            "packages" to
                mapOf(
                    "node_modules/esbuild" to mapOf("hasInstallScript" to true),
                    "node_modules/@scope/native" to mapOf("hasInstallScript" to true),
                    "node_modules/left-pad" to mapOf("version" to "1.3.0"),
                ),
        )

    @Test
    fun `lists the packages with install scripts, by name, sorted`() {
        assertEquals(listOf("@scope/native", "esbuild"), InstallScripts.packages(lockfile, emptyList()))
    }

    @Test
    fun `leaves out packages vetted as portable`() {
        assertEquals(listOf("@scope/native"), InstallScripts.packages(lockfile, listOf("esbuild")))
    }

    @Test
    fun `finds none in a lockfile without packages`() {
        assertEquals(emptyList<String>(), InstallScripts.packages(emptyMap<String, Any>(), emptyList()))
    }
}
