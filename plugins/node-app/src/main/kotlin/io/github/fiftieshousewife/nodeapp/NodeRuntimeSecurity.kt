package io.github.fiftieshousewife.nodeapp

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * `nodeApp { security { … } }`: an organisation's changes to how the embedded Node.js trusts and
 * negotiates TLS, baked into the launcher so they apply wherever the archive runs.
 */
abstract class NodeRuntimeSecurity {
    /**
     * Approved CA certificates (PEM or DER, one or many per file; directories are searched), trusted
     * in addition to Node's bundled CAs: the build writes them to `security/trusted-cas.pem` and the
     * launcher sets `NODE_EXTRA_CA_CERTS` to it.
     */
    abstract val trustedCertificates: ConfigurableFileCollection

    /** The lowest TLS version Node accepts: `1.2` or `1.3` (`--tls-min-v1.x`). Unset keeps Node's default. */
    abstract val tlsMinVersion: Property<String>

    /** An OpenSSL cipher list replacing Node's default (`--tls-cipher-list`). Unset keeps Node's default. */
    abstract val tlsCipherList: Property<String>

    internal companion object {
        private val VERSIONS = setOf("1.2", "1.3")

        /** The Node options these settings add to the launcher, before `nodeArgs`. */
        fun options(tlsMinVersion: String?, tlsCipherList: String?): List<String> {
            if (tlsMinVersion != null && tlsMinVersion !in VERSIONS) {
                throw GradleException("nodeApp.security.tlsMinVersion is '$tlsMinVersion'; use 1.2 or 1.3")
            }
            return listOfNotNull(
                tlsMinVersion?.let {
                    "--tls-min-v$it"
                },
                tlsCipherList?.let { "--tls-cipher-list=$it" },
            )
        }
    }
}
