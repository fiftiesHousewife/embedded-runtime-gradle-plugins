package io.github.fiftieshousewife.javaapp

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleSetsTest {
    private val small = listOf("java.base", "jdk.httpserver")
    private val large = listOf("java.base", "jdk.httpserver", "java.sql", "java.logging")
    private val sets = mapOf("large" to large, "small" to small)

    @Test
    fun `chooses the smallest set holding every module the app needs`() {
        assertAll(
            { assertEquals(small, ModuleSets.choose(listOf("java.base"), sets, emptyList())) },
            { assertEquals(large, ModuleSets.choose(listOf("java.sql"), sets, emptyList())) },
        )
    }

    @Test
    fun `breaks a tie between sets of one size by name`() {
        val twins = mapOf("b" to listOf("java.base", "java.sql"), "a" to listOf("java.base", "java.xml"))
        assertEquals(listOf("java.base", "java.xml"), ModuleSets.choose(listOf("java.base"), twins, emptyList()))
    }

    @Test
    fun `falls back to exactly the app's modules, sorted, when no set holds them all`() {
        val needed = listOf("jdk.jdwp.agent", "java.base")
        assertEquals(listOf("java.base", "jdk.jdwp.agent"), ModuleSets.choose(needed, sets, emptyList()))
    }

    @Test
    fun `skips a set holding a forbidden module`() {
        val debuggable = mapOf("debug" to listOf("java.base", "jdk.jdwp.agent"), "large" to large)
        assertAll(
            { assertEquals(large, ModuleSets.choose(listOf("java.base"), debuggable, listOf("jdk.jdwp.agent"))) },
            {
                assertEquals(
                    listOf("java.base"),
                    ModuleSets.choose(listOf("java.base"), sets, listOf("jdk.httpserver")),
                )
            },
        )
    }

    @Test
    fun `drops a module from every set after the last release that has it`() {
        val entries = mapOf(
            "set.web" to "java.base, jdk.crypto.ec,\tjdk.httpserver",
            "lastFeature.jdk.crypto.ec" to "21",
        )
        val upTo21 = listOf("java.base", "jdk.crypto.ec", "jdk.httpserver")
        assertAll(
            { assertEquals(upTo21, ModuleSets.parse(entries, 21)["web"]) },
            { assertEquals(listOf("java.base", "jdk.httpserver"), ModuleSets.parse(entries, 25)["web"]) },
        )
    }

    @Test
    fun `the default sets each hold java base and no debugging or attach module`() {
        val defaults = ModuleSets.defaults(21)
        val unsafe = listOf("jdk.jdwp.agent", "jdk.attach", "jdk.management.agent")
        assertAll(
            { assertEquals(setOf("base", "service", "framework", "server"), defaults.keys) },
            { assertTrue(defaults.values.all { "java.base" in it }, defaults.toString()) },
            { assertTrue(defaults.values.none { set -> unsafe.any(set::contains) }, defaults.toString()) },
            { assertFalse(ModuleSets.defaults(25).values.any { "jdk.crypto.ec" in it }) },
        )
    }

    @Test
    fun `a Spring Boot app's modules fit the framework set`() {
        val springBoot = listOf(
            "java.base", "java.compiler", "java.datatransfer", "java.xml", "java.prefs", "java.desktop",
            "java.instrument", "java.logging", "java.management", "java.security.sasl", "java.naming",
            "java.net.http", "java.scripting", "java.security.jgss", "java.transaction.xa", "java.sql",
            "jdk.jfr", "jdk.unsupported",
        )
        val defaults = ModuleSets.defaults(21)
        assertEquals(defaults["framework"], ModuleSets.choose(springBoot, defaults, listOf("jdk.jdwp.agent")))
    }
}
