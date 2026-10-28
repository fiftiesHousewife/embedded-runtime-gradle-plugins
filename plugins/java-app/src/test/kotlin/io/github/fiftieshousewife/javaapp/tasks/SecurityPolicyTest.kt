package io.github.fiftieshousewife.javaapp.tasks

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class SecurityPolicyTest {
    private val original =
        Properties().apply {
            setProperty("jdk.tls.disabledAlgorithms", "SSLv3, TLSv1, RC4")
            setProperty("networkaddress.cache.ttl", "-1")
        }

    @Test
    fun `adds to a disabled-algorithm list without losing the JDK's own entries or repeating any`() {
        val policy =
            SecurityPolicy(
                emptyMap(),
                mapOf("jdk.tls.disabledAlgorithms" to listOf("TLS_RSA_X", "RC4")),
                false,
            )
        assertEquals(mapOf("jdk.tls.disabledAlgorithms" to "SSLv3, TLSv1, RC4, TLS_RSA_X"), policy.changes(original))
    }

    @Test
    fun `applies the properties, then the lock`() {
        val policy =
            SecurityPolicy(
                properties = mapOf("networkaddress.cache.ttl" to "60", "jdk.serialFilter" to "!*"),
                additions = emptyMap(),
                lock = true,
            )
        val changes = policy.changes(original)
        assertAll(
            { assertEquals("60", changes["networkaddress.cache.ttl"]) },
            { assertEquals("!*", changes["jdk.serialFilter"]) },
            { assertEquals("false", changes[SecurityPolicy.OVERRIDE]) },
        )
    }

    @Test
    fun `an addition builds on a list a property already set`() {
        val policy =
            SecurityPolicy(
                properties = mapOf("jdk.tls.disabledAlgorithms" to "SSLv3"),
                additions = mapOf("jdk.tls.disabledAlgorithms" to listOf("TLS_RSA_X")),
                lock = false,
            )
        assertEquals("SSLv3, TLS_RSA_X", policy.changes(original)["jdk.tls.disabledAlgorithms"])
    }

    @Test
    fun `changes nothing when the policy is empty`() {
        assertEquals(
            emptyMap<String, String>(),
            SecurityPolicy(emptyMap(), mapOf("x" to emptyList()), false).changes(original),
        )
    }

    @Test
    fun `appends the changes under a marker, escaping backslashes`() {
        assertEquals(
            "\n# --- Set by io.github.fiftieshousewife.java-app (see record.txt) ---\na=b\\\\c\n",
            SecurityPolicy.block(mapOf("a" to "b\\c"), "record.txt"),
        )
    }
}
