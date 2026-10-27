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

    fun certificateFiles(files: Collection<File>) =
        files.filter { it.extension.lowercase() in EXTENSIONS }.sortedBy { it.name }

    fun read(file: File): List<X509Certificate> = try {
        Files.newInputStream(file.toPath()).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            .filterIsInstance<X509Certificate>()
    } catch (e: CertificateException) {
        throw GradleException("${file.name} is not a PEM or DER certificate: ${e.message}", e)
    }

    fun alias(file: File, index: Int, count: Int, prefix: String = APPROVED) =
        "$prefix-" + file.nameWithoutExtension.lowercase() + if (count > 1) "-${index + 1}" else ""

    const val APPROVED = "approved"

    /** The runtime policy's certificates, kept apart from a project's so neither replaces the other. */
    const val POLICY = "policy"

    fun fingerprint(certificate: X509Certificate): String =
        FINGERPRINT.formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

    /** Returns one line per certificate for the customisation record. */
    fun install(cacerts: File, files: List<File>, replace: Boolean, prefix: String = APPROVED): List<String> {
        requireDistinctNames(files)
        val store = if (replace) {
            KeyStore.getInstance("PKCS12").apply {
                load(null, PASSWORD)
            }
        } else {
            KeyStore.getInstance(cacerts, PASSWORD)
        }
        val lines = files.flatMap { file -> add(store, file, prefix) }
        Files.newOutputStream(cacerts.toPath()).use { store.store(it, PASSWORD) }
        return lines
    }

    /** Aliases come from file names, so two files called `root.pem` would silently replace each other. */
    private fun requireDistinctNames(files: List<File>) {
        val clashes = files.groupBy { it.nameWithoutExtension.lowercase() }.filterValues { it.size > 1 }
        if (clashes.isNotEmpty()) {
            val listed = clashes.values.joinToString("; ") { group -> group.joinToString(", ") }
            throw GradleException(
                "Trusted certificate files need distinct names, which become keystore aliases: $listed",
            )
        }
    }

    private fun add(store: KeyStore, file: File, prefix: String): List<String> {
        val certificates = read(file)
        return certificates.mapIndexed { index, certificate ->
            val alias = alias(file, index, certificates.size, prefix)
            store.setCertificateEntry(alias, certificate)
            "  $alias  ${certificate.subjectX500Principal.name}  SHA-256 ${fingerprint(certificate)}"
        }
    }
}
