package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.provider.ProviderFactory
import java.io.File
import java.net.URI

/**
 * Where JDK archives come from, first match wins: a JDK configured by path for one feature release
 * and target ([localJdk]); a shared local directory ([LOCAL_PATH]); the curated BinaryRepository tree;
 * the chosen [distribution]'s own site.
 *
 * The compile toolchain resolver and the archive's runtime both ask this class, so compilation and
 * packaging can never disagree about where a JDK comes from.
 */
class JdkSources(val distribution: JdkDistribution, private val location: JdkLocation = JdkLocation.Public()) {
    val repository: RuntimeRepository
        get() =
            when (location) {
                is JdkLocation.Public -> {
                    RuntimeRepository(
                        name = "${distribution.id}Jdks",
                        baseUrl = RuntimeRepository.slash(location.url ?: distribution.site.baseUrl),
                        artifactPattern = location.pattern ?: distribution.site.pattern,
                        group = distribution.group,
                    )
                }

                is JdkLocation.Curated -> {
                    RuntimeRepository(
                        name = location.name,
                        baseUrl = location.baseUrl,
                        artifactPattern = location.pattern ?: RuntimeRepository.CURATED_PATTERN,
                        group = distribution.group,
                    )
                }
            }

    fun artifact(version: JdkVersion, target: Target): RuntimeArtifact = when (location) {
        is JdkLocation.Public -> distribution.artifact(version, target)

        // Renamed on upload, so the vendor's own naming is irrelevant here.
        is JdkLocation.Curated -> RuntimeArtifact.curated(distribution.group, "jdk", version.underscored, target)
    }

    /**
     * The separate `jmods/` archive for a release whose JDK lacks it ([JdkDistribution.separateJmods]).
     * In a curated tree it sits beside the JDK as `jmods`:
     * `temurin/jmods/25.0.4.1_1/jmods-25.0.4.1_1-linux-x64.tar.gz`.
     */
    fun jmodsArtifact(version: JdkVersion, target: Target): RuntimeArtifact = when (location) {
        is JdkLocation.Public -> distribution.jmodsArtifact(version, target)
        is JdkLocation.Curated -> RuntimeArtifact.curated(distribution.group, "jmods", version.underscored, target)
    }

    fun downloadUri(version: JdkVersion, target: Target): URI = repository.downloadUri(artifact(version, target))

    companion object {
        /** Which JDK vendor to compile with and embed: see [JdkDistribution]. */
        const val DISTRIBUTION = "javaApp.jdk.distribution"

        /** `javaApp.jdk.21=21.0.13+9` pins (or overrides the catalogue pin for) a feature release. */
        const val VERSION_PREFIX = "javaApp.jdk."

        /**
         * Where JDK archives live without BinaryRepository, and how they are laid out under that root.
         * Together these reach any tree — a mirror, an internal build server — without a plugin release.
         */
        const val PUBLIC_URL = "javaApp.jdk.url"
        const val PUBLIC_PATTERN = "javaApp.jdk.pattern"

        /** Ivy pattern for the curated BinaryRepository tree, when it cannot use the default layout. */
        const val BINARY_REPO_PATTERN = "javaApp.jdk.binaryRepoPattern"

        /** An absolute path to a shared directory of JDK archives, laid out like the curated BinaryRepository tree. */
        const val LOCAL_PATH = "javaApp.jdk.localPath"
        const val LOCAL_PATTERN = "javaApp.jdk.localPattern"

        /**
         * `javaApp.jdk.21.linux-x64.path`: one JDK, as an installed directory or an archive. It wins
         * over every other source for that feature release and target.
         */
        fun pathProperty(feature: Int, target: Target) = "$VERSION_PREFIX$feature.${target.id}.path"

        /** The JDK configured at [pathProperty], if any. */
        fun localJdk(providers: ProviderFactory, feature: Int, target: Target): File? =
            providers.setting(pathProperty(feature, target))?.let(::File)

        fun from(providers: ProviderFactory) = JdkSources(
            providers.setting(DISTRIBUTION)?.let(JdkDistribution::parse) ?: JdkDistribution.DEFAULT,
            location(providers),
        )

        /** First match wins: a shared local directory, then BinaryRepository, then the vendor's own site. */
        private fun location(providers: ProviderFactory): JdkLocation {
            val localPath = providers.setting(LOCAL_PATH)
            val binaryRepo = BinaryRepository.from(providers)
            return when {
                localPath != null -> JdkLocation.local(File(localPath), providers.setting(LOCAL_PATTERN))
                binaryRepo != null -> JdkLocation.binaryRepo(binaryRepo, providers.setting(BINARY_REPO_PATTERN))
                else -> JdkLocation.Public(providers.setting(PUBLIC_URL), providers.setting(PUBLIC_PATTERN))
            }
        }
    }
}

/** Where a distribution's JDK archives are read from. */
sealed interface JdkLocation {
    /** The vendor's own site, or a mirror of it: the vendor's file names, under an optional URL and pattern. */
    data class Public(val url: String? = null, val pattern: String? = null) : JdkLocation

    /** Archives renamed to the curated layout: the binary repository's runtimes repository, or a local directory. */
    data class Curated(
        val name: String,
        val baseUrl: URI,
        /** Override of [RuntimeRepository.CURATED_PATTERN]. */
        val pattern: String? = null,
    ) : JdkLocation

    companion object {
        fun binaryRepo(binaryRepo: BinaryRepository, pattern: String? = null) =
            Curated("binaryRepoJdks", binaryRepo.runtimesUrl, pattern)

        fun local(directory: File, pattern: String? = null) =
            Curated("localJdks", RuntimeRepository.slash(directory.absoluteFile.toURI().toString()), pattern)
    }
}
