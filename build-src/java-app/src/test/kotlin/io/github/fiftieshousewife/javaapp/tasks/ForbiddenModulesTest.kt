package io.github.fiftieshousewife.javaapp.tasks

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForbiddenModulesTest {
    private val release = "JAVA_VERSION=\"21.0.12\"\nMODULES=\"java.base jdk.httpserver jdk.jdwp.agent\"\n"

    @Test
    fun `finds the forbidden modules a runtime's release file lists`() {
        assertAll(
            {
                assertEquals(
                    listOf("jdk.jdwp.agent"),
                    ForbiddenModules.present(release, listOf("jdk.jdwp.agent", "jdk.attach")),
                )
            },
            { assertEquals(emptyList<String>(), ForbiddenModules.present(release, listOf("jdk.attach"))) },
        )
    }

    @Test
    fun `does not mistake a module for one whose name it contains`() {
        assertEquals(emptyList<String>(), ForbiddenModules.present("MODULES=\"java.base\"", listOf("java")))
    }
}
