package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Artifactory
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.provider.ProviderFactory
import java.net.URI

/**
 * Where JDK archives come from: the chosen [distribution] from its own site, or from the curated
 * Artifactory tree when one is configured.
 *
 * The compile toolchain resolver and the archive's runtime both ask this class, so compilation and
 * packaging can never disagree about where a JDK comes from.
 */
class JdkSources(
    val distribution: JdkDistribution,
    private val artifactory: Artifactory?,
    /** Overrides of the distribution's own site; null means its default. */
    private val publicUrl: String? = null,
    private val publicPattern: String? = null,
    /** Override of [RuntimeRepository.CURATED_PATTERN]; null means the default curated layout. */
    private val artifactoryPattern: String? = null,
) {
    val repository: RuntimeRepository
        get() =
            if (artifactory == null) {
                RuntimeRepository(
                    name = "${distribution.id}Jdks",
                    baseUrl = RuntimeRepository.slash(publicUrl ?: distribution.defaultBaseUrl),
                    artifactPattern = publicPattern ?: distribution.defaultPattern,
                    group = distribution.group,
                )
            } else {
                RuntimeRepository(
                    name = "artifactoryJdks",
                    baseUrl = artifactory.runtimesUrl,
                    artifactPattern = artifactoryPattern ?: RuntimeRepository.CURATED_PATTERN,
                    group = distribution.group,
                )
            }

    fun artifact(
        version: JdkVersion,
        target: Target,
    ): RuntimeArtifact =
        if (artifactory == null) {
            distribution.artifact(version, target)
        } else {
            // Renamed on upload, so the vendor's own naming is irrelevant here.
            RuntimeArtifact.curated(distribution.group, "jdk", version.underscored, target)
        }

    fun downloadUri(
        version: JdkVersion,
        target: Target,
    ): URI = repository.downloadUri(artifact(version, target))

    companion object {
        /** Which JDK vendor to compile with and embed: see [JdkDistribution]. */
        const val DISTRIBUTION = "javaApp.jdk.distribution"

        /** `javaApp.jdk.21=21.0.13+9` pins (or overrides the catalogue pin for) a feature release. */
        const val VERSION_PREFIX = "javaApp.jdk."

        /**
         * Where JDK archives live without Artifactory, and how they are laid out under that root.
         * Together these reach any tree — a mirror, an internal build server — without a plugin release.
         */
        const val PUBLIC_URL = "javaApp.jdk.url"
        const val PUBLIC_PATTERN = "javaApp.jdk.pattern"

        /** Ivy pattern for the curated Artifactory tree, when it cannot use the default layout. */
        const val ARTIFACTORY_PATTERN = "javaApp.jdk.artifactoryPattern"

        fun from(providers: ProviderFactory) =
            JdkSources(
                distribution = providers.setting(DISTRIBUTION)?.let(JdkDistribution::parse) ?: JdkDistribution.DEFAULT,
                artifactory = Artifactory.from(providers),
                publicUrl = providers.setting(PUBLIC_URL),
                publicPattern = providers.setting(PUBLIC_PATTERN),
                artifactoryPattern = providers.setting(ARTIFACTORY_PATTERN),
            )
    }
}
