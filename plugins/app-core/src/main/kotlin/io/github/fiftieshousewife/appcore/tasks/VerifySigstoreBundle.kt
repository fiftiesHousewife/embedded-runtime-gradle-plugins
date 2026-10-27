package io.github.fiftieshousewife.appcore.tasks

import dev.sigstore.KeylessVerificationException
import dev.sigstore.KeylessVerifier
import dev.sigstore.TrustedRootProvider
import dev.sigstore.VerificationOptions
import dev.sigstore.VerificationOptions.CertificateMatcher
import dev.sigstore.bundle.Bundle
import dev.sigstore.strings.StringMatcher
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File

/**
 * Checks one archive against its Sigstore bundle. Runs in a worker whose classpath holds
 * sigstore-java, which the plugins otherwise never load; see [AppSmokeTest.signer].
 */
abstract class VerifySigstoreBundle : WorkAction<VerifySigstoreBundle.Parameters> {
    interface Parameters : WorkParameters {
        val archive: RegularFileProperty
        val bundle: RegularFileProperty
        val identity: Property<String>
        val issuer: Property<String>
        val trustedRoot: RegularFileProperty
    }

    override fun execute() {
        SigstoreVerification.verify(
            parameters.archive.get().asFile,
            parameters.bundle.get().asFile,
            SignerIdentity(parameters.identity.get(), parameters.issuer.get()),
            parameters.trustedRoot.orNull?.asFile,
        )
    }
}

/** Who must have signed: the certificate's identity, a regex, and the OIDC issuer that vouched for it. */
data class SignerIdentity(val identity: String, val issuer: String) : java.io.Serializable

internal object SigstoreVerification {
    /**
     * Verifies [archive] against [bundle]: a valid signature by a Fulcio certificate whose identity
     * matches [signer], recorded in the transparency log. [trustedRoot] is Sigstore's
     * `trusted_root.json`, for machines that can't fetch it; without it the public-good instance's
     * root is fetched and kept up to date through TUF.
     */
    fun verify(archive: File, bundle: File, signer: SignerIdentity, trustedRoot: File?) {
        val verifier = KeylessVerifier.builder().run {
            if (trustedRoot ==
                null
            ) {
                sigstorePublicDefaults()
            } else {
                trustedRootProvider(TrustedRootProvider.from(trustedRoot.toPath()))
            }
        }.build()
        val options = VerificationOptions.builder()
            .addCertificateMatchers(
                CertificateMatcher.fulcio()
                    .subjectAlternativeName(StringMatcher.regex(signer.identity))
                    .issuer(StringMatcher.string(signer.issuer))
                    .build(),
            )
            .build()
        try {
            verifier.verify(archive.toPath(), Bundle.from(bundle.toPath(), Charsets.UTF_8), options)
        } catch (e: KeylessVerificationException) {
            throw GradleException(
                "${archive.name} is not signed by ${signer.identity} (issuer ${signer.issuer}): ${e.message}",
                e,
            )
        }
    }
}
