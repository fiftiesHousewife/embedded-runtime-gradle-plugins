package io.github.fiftieshousewife.javaapp

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

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

    /** Trust only [trustedCertificates], instead of adding them to the JDK's own CAs. Default false. */
    abstract val replaceDefaultTrust: Property<Boolean>

    /**
     * Properties files applied over the runtime's `conf/security/java.security`, in order: the
     * organisation's security policy, taken from wherever it is published.
     */
    abstract val propertiesFiles: ConfigurableFileCollection

    /** Security properties set after [propertiesFiles], e.g. `"networkaddress.cache.ttl" to "30"`. */
    abstract val properties: MapProperty<String, String>

    /** Added to `jdk.tls.disabledAlgorithms`, keeping the JDK's own list: weak cipher suites, protocols, key sizes. */
    abstract val disabledTlsAlgorithms: ListProperty<String>

    /** Added to `jdk.certpath.disabledAlgorithms`: algorithms and key sizes refused in certificate chains. */
    abstract val disabledCertPathAlgorithms: ListProperty<String>

    /** Added to `jdk.jar.disabledAlgorithms`: algorithms refused for signed jars. */
    abstract val disabledJarAlgorithms: ListProperty<String>

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
}
