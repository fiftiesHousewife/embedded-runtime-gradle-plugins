package io.github.fiftieshousewife.javaapp.tasks

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

class TrustedCertificatesTest {
    @TempDir
    lateinit var dir: File

    private fun resource(name: String) =
        File(TrustedCertificatesTest::class.java.getResource("/certificates/$name")!!.toURI())

    @Test
    fun `reads one certificate, or every certificate in a PEM bundle`() {
        assertAll(
            { assertEquals(1, TrustedCertificates.read(resource("acme-root.pem")).size) },
            { assertEquals(2, TrustedCertificates.read(resource("acme-bundle.pem")).size) },
        )
    }

    @Test
    fun `names a file that is not a certificate`() {
        val failure = assertThrows(GradleException::class.java) { TrustedCertificates.read(resource("broken.pem")) }
        assertTrue(failure.message!!.startsWith("broken.pem is not a PEM or DER certificate"), failure.message)
    }

    @Test
    fun `aliases by file name, numbering certificates that share a file`() {
        val bundle = File("ACME-Bundle.pem")
        assertAll(
            { assertEquals("approved-acme-bundle", TrustedCertificates.alias(bundle, 0, 1)) },
            { assertEquals("approved-acme-bundle-2", TrustedCertificates.alias(bundle, 1, 2)) },
        )
    }

    @Test
    fun `fingerprints with SHA-256 in upper-case colon-separated hex`() {
        val certificate = TrustedCertificates.read(resource("acme-root.pem")).single()
        assertEquals(
            "8D:0E:B1:5B:58:DC:24:4F:D4:FB:67:53:A6:DC:3C:EB:88:2D:1E:63:32:5C:21:26:00:FA:DF:B1:B8:DB:76:5C",
            TrustedCertificates.fingerprint(certificate),
        )
    }

    @Test
    fun `keeps only certificate files, in a stable order`() {
        val files = listOf(File("b.crt"), File("README.md"), File("a.pem"), File("c.der"))
        assertEquals(listOf("a.pem", "b.crt", "c.der"), TrustedCertificates.certificateFiles(files).map { it.name })
    }

    @Test
    fun `replacing the trust store leaves only the approved certificates`() {
        val cacerts = jdkCacerts("cacerts")
        val lines = TrustedCertificates.install(cacerts, listOf(resource("acme-bundle.pem")), replace = true)
        val store = KeyStore.getInstance(cacerts, "changeit".toCharArray())
        assertAll(
            {
                assertEquals(
                    listOf("approved-acme-bundle-1", "approved-acme-bundle-2"),
                    store.aliases().toList().sorted(),
                )
            },
            { assertEquals(2, lines.size) },
            { assertTrue(Files.size(cacerts.toPath()) > 0) },
        )
    }

    @Test
    fun `the same certificates give the same bytes, whether added or replacing the JDK's`() {
        val certificates = listOf(resource("acme-bundle.pem"))
        val written = listOf(true, true, false, false).mapIndexed { index, replace ->
            jdkCacerts("cacerts-$index").also { TrustedCertificates.install(it, certificates, replace) }
        }.map { Files.readAllBytes(it.toPath()).toList() }
        assertAll(
            { assertEquals(written[0], written[1]) },
            { assertEquals(written[2], written[3]) },
        )
    }

    /** Writable, as `customiseRuntime` makes its copy of the runtime: some JDKs ship `cacerts` read-only. */
    private fun jdkCacerts(name: String): File = File(dir, name).also {
        Files.copy(Path.of(System.getProperty("java.home"), "lib", "security", "cacerts"), it.toPath())
        it.setWritable(true)
    }

    @Test
    fun `two files with the same name are refused rather than one silently replacing the other`() {
        val elsewhere = dir.resolve("other/acme-root.pem").apply { parentFile.mkdirs() }
        resource("acme-root.pem").copyTo(elsewhere)
        val cacerts = dir.resolve("cacerts")
        val failure =
            assertThrows(GradleException::class.java) {
                TrustedCertificates.install(cacerts, listOf(resource("acme-root.pem"), elsewhere), replace = true)
            }
        assertTrue(failure.message!!.contains("need distinct names"), failure.message)
    }

    @Test
    fun `policy certificates have aliases of their own`() {
        assertEquals(
            "policy-acme-root",
            TrustedCertificates.alias(File("acme-root.pem"), 0, 1, TrustedCertificates.POLICY),
        )
    }
}
