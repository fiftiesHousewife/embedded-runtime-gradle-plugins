package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.RuntimeSource
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import java.io.File
import java.net.URI

/** A JDK by path ([localJdk]) wins; else [SOURCE], else the curated binary repository, else the vendor's site. */
class JdkSources(
    val distribution: JdkDistribution,
    private val location: JdkLocation = JdkLocation.Public(),
    /** A YUM repository the JDK packages are installed from on servers; wins over [location]. */
    val yum: JdkYumSource? = null,
) {
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

                is JdkLocation.Curated -> RuntimeRepository.curated(
                    location.name,
                    location.baseUrl,
                    location.pattern ?: RuntimeRepository.CURATED_PATTERN,
                    distribution.group,
                )
            }

    fun artifact(version: JdkVersion, target: Target): RuntimeArtifact = when (location) {
        is JdkLocation.Public -> distribution.artifact(version, target)

        // Renamed on upload, so the vendor's own naming is irrelevant here.
        is JdkLocation.Curated ->
            repository.curated("jdk", location.revision(version), target, version.tokens)
    }

    /** Gradle can't provision a toolchain from an RPM, so the resolver leaves those to the machine's own JDKs. */
    val archivesAreRpms: Boolean get() = yum != null || repository.artifactPattern.endsWith(".rpm", ignoreCase = true)

    /** For [JdkDistribution.separateJmods]; curated: `temurin/jmods/25.0.4.1_1/jmods-25.0.4.1_1-linux-x64.tar.gz`. */
    fun jmodsArtifact(version: JdkVersion, target: Target): RuntimeArtifact = when (location) {
        is JdkLocation.Public -> distribution.jmodsArtifact(version, target)

        is JdkLocation.Curated -> repository.curated(
            "jmods",
            location.revision(version),
            target,
            version.tokens,
        )
    }

    fun downloadUri(version: JdkVersion, target: Target): URI = repository.downloadUri(artifact(version, target))

    companion object {
        const val DISTRIBUTION = "javaApp.jdk.distribution"

        const val VERSION_PREFIX = "javaApp.jdk."

        /** Where JDKs come from ([RuntimeSource]); [PATTERN] names the files, or the package, in it. */
        const val SOURCE = "javaApp.jdk.source"
        const val PATTERN = "javaApp.jdk.pattern"

        /** Reuse the compile toolchain's JDK when it is exactly the pin, rather than download another copy. */
        const val REUSE_TOOLCHAIN = "javaApp.jdk.reuseToolchain"

        /** A directory or an archive; it wins over every other source for that feature release and target. */
        fun pathProperty(feature: Int, target: Target) = "$VERSION_PREFIX$feature.${target.id}.path"

        fun localJdk(providers: ProviderFactory, feature: Int, target: Target): File? =
            providers.setting(pathProperty(feature, target))?.let(::File)

        fun from(providers: ProviderFactory): JdkSources {
            val distribution = providers.setting(DISTRIBUTION)?.let(JdkDistribution::parse) ?: JdkDistribution.DEFAULT
            val source = RuntimeSource.parse(SOURCE, providers.setting(SOURCE))
            val pattern = providers.setting(PATTERN)
            val binaryRepo = BinaryRepository.from(providers)
            return if (source is RuntimeSource.Yum) {
                JdkSources(
                    distribution,
                    location(RuntimeSource.Default, null, binaryRepo),
                    yum(source, pattern, binaryRepo, distribution),
                )
            } else {
                JdkSources(distribution, location(source, pattern, binaryRepo))
            }
        }

        private fun yum(
            source: RuntimeSource.Yum,
            pattern: String?,
            binaryRepo: BinaryRepository?,
            distribution: JdkDistribution,
        ): JdkYumSource {
            val url = if (source.isUrl) {
                URI.create(source.repository + "/")
            } else {
                requireBinaryRepo(binaryRepo, source.repository).repo(source.repository)
            }
            val name = pattern ?: distribution.yumPackage ?: throw GradleException(
                "$SOURCE is a YUM repository; set $PATTERN to the JDK package's name in it " +
                    "(e.g. java-[feature]-openjdk-devel)",
            )
            return JdkYumSource(url, name)
        }

        internal fun location(source: RuntimeSource, pattern: String?, binaryRepo: BinaryRepository?): JdkLocation =
            when (source) {
                is RuntimeSource.Mirror -> JdkLocation.Public(source.url, pattern)

                is RuntimeSource.Directory -> JdkLocation.local(source.uri).copy(pattern = pattern)

                is RuntimeSource.Repository ->
                    JdkLocation.binaryRepo(
                        requireBinaryRepo(binaryRepo, source.name),
                        source.name,
                    ).copy(pattern = pattern)

                else -> binaryRepo?.let { JdkLocation.binaryRepo(it).copy(pattern = pattern) }
                    ?: JdkLocation.Public(null, pattern)
            }

        private fun requireBinaryRepo(binaryRepo: BinaryRepository?, name: String) =
            binaryRepo ?: throw GradleException(
                "$SOURCE names the repository $name beside the binary repository, but none is configured: " +
                    "set binaryRepo.repository or binaryRepo.url, or give $SOURCE as a URL",
            )
    }
}

sealed interface JdkLocation {
    /** The vendor's own site, or a mirror of it: the vendor's file names, under an optional URL and pattern. */
    data class Public(val url: String? = null, val pattern: String? = null) : JdkLocation

    /** Archives renamed to the curated layout: the binary repository's runtimes repository, or a local directory. */
    data class Curated(val name: String, val baseUrl: URI, val pattern: String? = null) : JdkLocation {
        fun revision(version: JdkVersion): String = version.underscored
    }

    companion object {
        fun binaryRepo(binaryRepo: BinaryRepository, repository: String? = null) =
            Curated("binaryRepoJdks", repository?.let(binaryRepo::repo) ?: binaryRepo.runtimesUrl)

        fun local(directory: URI) = Curated("localJdks", directory)
    }
}

/** The package's version is the pin without its build (`21.0.5`). */
data class JdkYumSource(val repository: URI, val packageName: String) {
    fun packageName(feature: Int): String = packageName.replace("[feature]", feature.toString())

    fun version(version: JdkVersion): String = version.value.substringBefore('+')
}
