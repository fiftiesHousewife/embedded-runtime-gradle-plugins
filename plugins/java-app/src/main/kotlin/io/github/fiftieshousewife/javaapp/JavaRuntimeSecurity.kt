package io.github.fiftieshousewife.javaapp

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import java.net.URI

/**
 * `javaApp { security { … } }`: an organisation's changes to the JDK it was given, applied to every
 * target's runtime after `jlink` by `customiseRuntime<Target>`. Every setting takes any Gradle file
 * source, so certificates and policy can come from the repository, a shared directory, or a
 * dependency resolved from BinaryRepository.
 */
abstract class JavaRuntimeSecurity {
    /**
     * Approved CA certificates (PEM or DER, one or many per file; directories are searched for
     * `.pem`, `.crt`, `.cer` and `.der`), added to the runtime's `lib/security/cacerts`.
     */
    abstract val trustedCertificates: ConfigurableFileCollection

    /**
     * Approved CA certificates to fetch, by URI (`https:` or `file:`), each pinned by the SHA-256 of
     * the file so a changed certificate fails the build instead of being trusted. Added with
     * [trustedCertificate]. Fetched through the JVM's proxy settings; the binary repository's
     * credentials are sent only to URIs under `binaryRepo.url`.
     */
    abstract val trustedCertificateUris: MapProperty<String, String>

    /** `trustedCertificate(uri("https://pki.example.com/root-ca.pem"), sha256 = "9f86d081…")` */
    fun trustedCertificate(uri: URI, sha256: String) =
        trustedCertificateUris.put(uri.toString(), sha256.lowercase().replace(":", ""))

    /** Trust only [trustedCertificates], instead of adding them to the JDK's own CAs. Default false. */
    abstract val replaceDefaultTrust: Property<Boolean>

    /**
     * Security properties set in the runtime's `conf/security/java.security`, e.g.
     * `"networkaddress.cache.ttl" to "30"`; a file's worth is `properties.putAll(…)`.
     */
    abstract val properties: MapProperty<String, String>

    /** The algorithms added with [disableAlgorithms], by the `java.security` list they go in. */
    abstract val disabledAlgorithms: MapProperty<String, List<String>>

    /**
     * Adds to one of the JDK's disabled-algorithm lists, keeping the JDK's own entries: `tls`
     * (`jdk.tls.disabledAlgorithms`: cipher suites, protocols, key sizes), `certpath` (refused in
     * certificate chains) or `jar` (refused for signed jars).
     * `disableAlgorithms("tls", "TLSv1.1", "RSA keySize < 3072")`.
     */
    fun disableAlgorithms(kind: String, vararg algorithms: String) {
        val list = DISABLED_ALGORITHM_LISTS[kind.lowercase()]
            ?: throw GradleException("disableAlgorithms takes ${DISABLED_ALGORITHM_LISTS.keys}, not '$kind'")
        disabledAlgorithms.put(list, disabledAlgorithms.get()[list].orEmpty() + algorithms)
    }

    /**
     * Sets `security.overridePropertiesFile=false`, so the policy cannot be loosened at runtime with
     * `-Djava.security.properties`. Default false.
     */
    abstract val lockProperties: Property<Boolean>

    /**
     * Modules the runtime must never contain; the build fails if one is linked. Default
     * `jdk.jdwp.agent`, the remote debugger.
     */
    abstract val forbiddenModules: ListProperty<String>

    private companion object {
        val DISABLED_ALGORITHM_LISTS = listOf("tls", "certpath", "jar").associateWith { "jdk.$it.disabledAlgorithms" }
    }
}
