package io.github.fiftieshousewife.nodeapp.tasks

import io.github.fiftieshousewife.appcore.RuntimePolicySource
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
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
import javax.inject.Inject

/**
 * `nodeTrustedCertificates`: every approved CA certificate, PEM or DER, from `nodeApp.security` and
 * then the organisation's runtime policy, in one PEM bundle, the form `NODE_EXTRA_CA_CERTS` reads.
 * Writes nothing when there are none, and the launcher then sets no `NODE_EXTRA_CA_CERTS`.
 */
@CacheableTask
abstract class BundleTrustedCertificates
@Inject
constructor(
    private val fs: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val certificates: ConfigurableFileCollection

    /** The organisation's runtime policy, if one is configured: its `certificates/` are trusted too. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policy: ConfigurableFileCollection

    @get:OutputFile abstract val bundle: RegularFileProperty

    @TaskAction
    fun write() {
        val policyCertificates =
            RuntimePolicySource.load(policy.files, fs, archives, temporaryDir.resolve("policy"))?.certificates.orEmpty()
        val files = certificateFiles(certificates.asFileTree.files) + certificateFiles(policyCertificates.toSet())
        val out = bundle.get().asFile
        out.delete()
        if (files.isEmpty()) return
        out.writeText(
            files.flatMap(::read).distinctBy { it.encoded.contentHashCode() }.joinToString("") {
                "-----BEGIN CERTIFICATE-----\n${ENCODER.encodeToString(it.encoded)}\n-----END CERTIFICATE-----\n"
            },
        )
    }

    private fun certificateFiles(files: Set<File>) =
        files.filter { it.extension.lowercase() in EXTENSIONS }.sortedBy { it.name }

    private fun read(file: File): List<X509Certificate> = try {
        file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            .filterIsInstance<X509Certificate>()
    } catch (e: CertificateException) {
        throw GradleException("${file.name} is not a PEM or DER certificate: ${e.message}", e)
    }

    private companion object {
        val EXTENSIONS = setOf("pem", "crt", "cer", "der")
        val ENCODER: Base64.Encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
    }
}
