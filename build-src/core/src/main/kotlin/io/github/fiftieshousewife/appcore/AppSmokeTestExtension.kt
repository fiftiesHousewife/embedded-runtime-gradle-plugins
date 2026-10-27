package io.github.fiftieshousewife.appcore

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import java.time.Duration

/**
 * `appSmokeTest { … }` in a build applying `io.github.fiftieshousewife.app-smoke-test`. Each setting
 * defaults to the `-PappSmokeTest.<name>` property of the same name.
 */
abstract class AppSmokeTestExtension {
    /**
     * A regular expression every archive's Sigstore certificate identity must match before the
     * archive is started, e.g. one release workflow of one repository. Unset: no signature check.
     */
    abstract val signerIdentity: Property<String>

    /** The OIDC issuer that identity must come from. Default: GitHub Actions'. */
    abstract val signerIssuer: Property<String>

    /** A copy of Sigstore's `trusted_root.json`, for a machine that can't fetch it. */
    abstract val sigstoreTrustedRoot: RegularFileProperty

    /**
     * The runtime policy every archive must comply with, a directory or zip; the
     * `-PappSmokeTest.securityPolicy` property, set per machine, wins over this.
     */
    abstract val securityPolicy: Property<String>

    /** The policy as the `group:name:version` of a zip; `-PappSmokeTest.securityPolicyArtifact` wins. */
    abstract val securityPolicyArtifact: Property<String>

    /** How long a started archive may take to report healthy. Default 60 seconds. */
    abstract val healthTimeout: Property<Duration>

    companion object {
        const val NAME = "appSmokeTest"
    }
}
