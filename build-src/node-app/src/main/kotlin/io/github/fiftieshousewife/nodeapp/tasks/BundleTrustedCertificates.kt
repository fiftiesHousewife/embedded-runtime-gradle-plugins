package io.github.fiftieshousewife.nodeapp.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * `nodeTrustedCertificates`: every approved CA certificate, PEM or DER, in one PEM bundle, the form
 * `NODE_EXTRA_CA_CERTS` reads. Skipped when there are none.
 */
@CacheableTask
abstract class BundleTrustedCertificates : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val certificates: ConfigurableFileCollection

    @get:OutputFile abstract val bundle: RegularFileProperty

    init {
        onlyIf("there are trusted certificates") { !certificates.isEmpty }
    }

    @TaskAction
    fun write() {
        val pem = certificates.asFileTree.files.filter { it.extension.lowercase() in EXTENSIONS }.sortedBy { it.name }
            .flatMap(::read)
            .joinToString("") {
                "-----BEGIN CERTIFICATE-----\n${ENCODER.encodeToString(it.encoded)}\n-----END CERTIFICATE-----\n"
            }
        bundle.get().asFile.writeText(pem)
    }

    private fun read(file: File): List<X509Certificate> = try {
        file.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }.filterIsInstance<X509Certificate>()
    } catch (e: CertificateException) {
        throw GradleException("${file.name} is not a PEM or DER certificate: ${e.message}", e)
    }

    private companion object {
        val EXTENSIONS = setOf("pem", "crt", "cer", "der")
        val ENCODER: Base64.Encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
    }
}
