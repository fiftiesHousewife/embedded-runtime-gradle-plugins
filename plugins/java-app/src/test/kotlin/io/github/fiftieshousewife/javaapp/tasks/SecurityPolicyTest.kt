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

    private fun properties(vararg entries: Pair<String, String>) = Properties().apply {
        entries.forEach { (k, v) -> setProperty(k, v) }
    }

    @Test
    fun `adds to a disabled-algorithm list without losing the JDK's own entries or repeating any`() {
        val policy =
            SecurityPolicy(
                emptyList(),
                emptyMap(),
                mapOf("jdk.tls.disabledAlgorithms" to listOf("TLS_RSA_X", "RC4")),
                false,
            )
        assertEquals(mapOf("jdk.tls.disabledAlgorithms" to "SSLv3, TLSv1, RC4, TLS_RSA_X"), policy.changes(original))
    }

    @Test
    fun `applies files, then single properties, then the lock, later ones winning`() {
        val policy =
            SecurityPolicy(
                files = listOf(properties("networkaddress.cache.ttl" to "30", "jdk.serialFilter" to "!*")),
                properties = mapOf("networkaddress.cache.ttl" to "60"),
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
    fun `an addition builds on a list a policy file already set`() {
        val policy =
            SecurityPolicy(
                files = listOf(properties("jdk.tls.disabledAlgorithms" to "SSLv3")),
                properties = emptyMap(),
                additions = mapOf("jdk.tls.disabledAlgorithms" to listOf("TLS_RSA_X")),
                lock = false,
            )
        assertEquals("SSLv3, TLS_RSA_X", policy.changes(original)["jdk.tls.disabledAlgorithms"])
    }

    @Test
    fun `changes nothing when the policy is empty`() {
        assertEquals(
            emptyMap<String, String>(),
            SecurityPolicy(emptyList(), emptyMap(), mapOf("x" to emptyList()), false).changes(original),
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
