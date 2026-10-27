package io.github.fiftieshousewife.javaapp.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JdepsModulesTest {
    @Test
    fun `reads the modules from jdeps' last line, ignoring warnings before it`() {
        assertEquals(
            listOf("java.base", "java.sql", "jdk.httpserver"),
            JdepsModules.parse("Warning: split package: a.b\njava.base,java.sql, jdk.httpserver"),
        )
    }
}
