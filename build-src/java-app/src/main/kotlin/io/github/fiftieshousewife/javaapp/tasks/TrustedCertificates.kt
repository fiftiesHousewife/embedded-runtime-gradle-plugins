package io.github.fiftieshousewife.javaapp.tasks

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.HexFormat

/** Approved CA certificates, read from PEM or DER files and added to a runtime's `cacerts`. */
internal object TrustedCertificates {
    /** The JDK's well-known default; trust entries are public, so it protects nothing. */
    private val PASSWORD = "changeit".toCharArray()
    private val EXTENSIONS = setOf("pem", "crt", "cer", "der")
    private val FINGERPRINT = HexFormat.ofDelimiter(":").withUpperCase()

    /** The certificate files among [files], in a stable order. */
    fun certificateFiles(files: Collection<File>) =
        files.filter { it.extension.lowercase() in EXTENSIONS }.sortedBy { it.name }

    /** Every certificate in [file], a PEM bundle or a single DER certificate. */
    fun read(file: File): List<X509Certificate> = try {
        Files.newInputStream(file.toPath()).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            .filterIsInstance<X509Certificate>()
    } catch (e: CertificateException) {
        throw GradleException("${file.name} is not a PEM or DER certificate: ${e.message}", e)
    }

    /** `approved-<file name>`, numbered when the file holds several certificates. */
    fun alias(file: File, index: Int, count: Int) =
        "approved-" + file.nameWithoutExtension.lowercase() + if (count > 1) "-${index + 1}" else ""

    fun fingerprint(certificate: X509Certificate): String =
        FINGERPRINT.formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

    /**
     * Adds every certificate in [files] to [cacerts] (or replaces its contents when [replace]) and
     * returns one line per certificate for the customisation record.
     */
    fun install(cacerts: File, files: List<File>, replace: Boolean): List<String> {
        val store = if (replace) {
            KeyStore.getInstance("PKCS12").apply {
                load(null, PASSWORD)
            }
        } else {
            KeyStore.getInstance(cacerts, PASSWORD)
        }
        val lines = files.flatMap { file -> add(store, file) }
        Files.newOutputStream(cacerts.toPath()).use { store.store(it, PASSWORD) }
        return lines
    }

    private fun add(store: KeyStore, file: File): List<String> {
        val certificates = read(file)
        return certificates.mapIndexed { index, certificate ->
            val alias = alias(file, index, certificates.size)
            store.setCertificateEntry(alias, certificate)
            "  $alias  ${certificate.subjectX500Principal.name}  SHA-256 ${fingerprint(certificate)}"
        }
    }
}
