package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory

class RuntimePolicyCheckTest {
    @TempDir
    lateinit var app: File

    private val policy = RuntimePolicy.load(File(RuntimePolicyCheckTest::class.java.getResource("/policy")!!.toURI()))

    /** What a fake unpacked app holds; the defaults comply with the test policy. */
    private data class Fake(
        val security: String = "networkaddress.cache.ttl=30\nsecurity.overridePropertiesFile=false\n" +
            "jdk.tls.disabledAlgorithms=SSLv3, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384\n",
        val modules: String = "java.base jdk.httpserver",
        val trustAcme: Boolean = true,
        val launcher: String = "# runtimeOptions = locked\nexec \"\$APP_HOME/runtime/bin/java\" -cp x Main\n",
        val purls: List<String> = listOf("pkg:maven/com.example/svc@1.0"),
    )

    /** Falls short of the test policy in every way it can. */
    private val nonCompliant =
        Fake(
            security = "networkaddress.cache.ttl=-1\njdk.tls.disabledAlgorithms=SSLv3\n",
            modules = "java.base jdk.jdwp.agent",
            trustAcme = false,
            launcher = "exec \"\$APP_HOME/runtime/bin/java\" '-javaagent:spy.jar' -cp x Main\n",
            purls = listOf("pkg:maven/org.bouncycastle/bctls-jdk18on@1.80"),
        )

    private fun app(fake: Fake = Fake()): File {
        app.resolve("runtime/conf/security").mkdirs()
        app.resolve("runtime/conf/security/java.security").writeText(fake.security)
        app.resolve("runtime/release").writeText("JAVA_VERSION=\"21\"\nMODULES=\"${fake.modules}\"\n")
        cacerts(fake.trustAcme)
        app.resolve("bin").mkdirs()
        app.resolve("bin/svc").writeText(fake.launcher)
        app.resolve("META-INF").mkdirs()
        val components = fake.purls.joinToString(",") { "{\"type\":\"library\",\"name\":\"x\",\"purl\":\"$it\"}" }
        app.resolve("META-INF/sbom.cdx.json")
            .writeText("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[$components]}")
        return app
    }

    private fun cacerts(trustAcme: Boolean) {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        if (trustAcme) store.setCertificateEntry("acme", acme())
        app.resolve("runtime/lib/security").mkdirs()
        app.resolve("runtime/lib/security/cacerts").outputStream().use { store.store(it, "changeit".toCharArray()) }
    }

    private fun acme() = policy.certificates.single().inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it)
    }

    @Test
    fun `a runtime built with the policy complies`() {
        assertEquals(emptyList<String>(), RuntimePolicyCheck.violations(policy, app()))
    }

    @Test
    fun `every way a runtime can fall short of the policy is reported`() {
        val violations =
            RuntimePolicyCheck.violations(
                policy,
                app(nonCompliant),
            ).joinToString("\n")
        assertAll(
            checks(
                listOf(
                    "networkaddress.cache.ttl is '-1'",
                    "jdk.tls.disabledAlgorithms does not include TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
                    "security.overridePropertiesFile is not false",
                    "forbidden module jdk.jdwp.agent",
                    "cacerts does not trust",
                    "does not lock runtime options",
                    "forbidden JVM option starting -javaagent",
                    "forbidden library pkg:maven/org.bouncycastle/bctls-jdk18on@1.80",
                ).map { expected ->
                    { assertTrue(violations.contains(expected), "missing '$expected' in:\n$violations") }
                },
            ),
        )
    }

    @Test
    fun `an archive without a Java runtime cannot comply`() {
        val node = app.resolve("node-app").apply { resolve("runtime/bin").mkdirs() }
        assertTrue(RuntimePolicyCheck.violations(policy, node).single().contains("no Java runtime"))
    }
}
