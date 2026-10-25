package io.github.fiftieshousewife.javaapp.tasks

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleSearchTest {
    private val listing =
        """
        jimage: /jdk/lib/modules

        Module: java.base
            java/lang/Object.class
            java/util/List.class

        Module: jdk.httpserver
            com/sun/net/httpserver/HttpServer.class
            META-INF/services/x
        """.trimIndent()

    private val packages = ModuleSearch.packagesToModules(listing)

    @Test
    fun `maps every package in a jimage listing to the module that holds it`() {
        assertEquals(
            mapOf("java.lang" to "java.base", "java.util" to "java.base", "com.sun.net.httpserver" to "jdk.httpserver"),
            packages,
        )
    }

    @Test
    fun `finds the module behind a class the app failed to load, in either spelling`() {
        assertAll(
            {
                assertEquals(
                    setOf("jdk.httpserver"),
                    ModuleSearch.missingModules("NoClassDefFoundError: com/sun/net/httpserver/HttpServer", packages),
                )
            },
            {
                assertEquals(
                    setOf("jdk.httpserver"),
                    ModuleSearch.missingModules("ClassNotFoundException: com.sun.net.httpserver.HttpServer", packages),
                )
            },
        )
    }

    @Test
    fun `recognises failures that name no class but mean one module`() {
        assertEquals(
            setOf("jdk.charsets"),
            ModuleSearch.missingModules("java.nio.charset.UnsupportedCharsetException: IBM037", packages),
        )
    }

    @Test
    fun `finds nothing in output that is not about a JDK class`() {
        assertEquals(
            emptySet<String>(),
            ModuleSearch.missingModules("NoClassDefFoundError: com/example/Missing", packages),
        )
    }

    @Test
    fun `reports the line to add, or that nothing is needed`() {
        assertAll(
            {
                assertTrue(
                    ModuleSearch.report(
                        "svc",
                        listOf("jdk.crypto.ec"),
                    ).contains("""additionalModules.addAll("jdk.crypto.ec")"""),
                )
            },
            {
                assertEquals(
                    "svc is healthy with its current modules; nothing to add.",
                    ModuleSearch.report("svc", emptyList()),
                )
            },
        )
    }
}
