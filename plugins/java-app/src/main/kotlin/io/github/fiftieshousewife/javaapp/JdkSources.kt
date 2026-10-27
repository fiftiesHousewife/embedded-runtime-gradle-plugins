package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.CustomTokens
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import io.github.fiftieshousewife.appcore.settingsPrefixedBy
import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import java.io.File
import java.net.URI

/** First match wins: a JDK by path ([localJdk]), [LOCAL_PATH], the curated binary repository, the vendor's site. */
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
            repository.curated("jdk", location.revision(version), target, location.tokens(version, target))
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
            location.tokens(version, target),
        )
    }

    fun downloadUri(version: JdkVersion, target: Target): URI = repository.downloadUri(artifact(version, target))

    companion object {
        const val DISTRIBUTION = "javaApp.jdk.distribution"

        const val VERSION_PREFIX = "javaApp.jdk."

        /** Together these reach any tree, a mirror or an internal build server, without a plugin release. */
        const val PUBLIC_URL = "javaApp.jdk.url"
        const val PUBLIC_PATTERN = "javaApp.jdk.pattern"

        const val BINARY_REPO_PATTERN = "javaApp.jdk.binaryRepoPattern"

        const val BINARY_REPO_NAME = "javaApp.jdk.binaryRepo"

        /** From [JdkVersion.format]'s tokens: `[feature]_[update]` names `jdk21_5…` for 21.0.5. */
        const val REVISION_FORMAT = "javaApp.jdk.revisionFormat"

        /** A repository beside the binary repository's (`yum-local/rhel/9`), or a full `https:` / `file:` URL. */
        const val YUM_REPO = "javaApp.jdk.yumRepo"

        const val YUM_PACKAGE = "javaApp.jdk.yumPackage"

        /** Reuse the compile toolchain's JDK when it is exactly the pin, rather than download another copy. */
        const val REUSE_TOOLCHAIN = "javaApp.jdk.reuseToolchain"

        /** `javaApp.jdk.token.plat.linux=l64`: an organisation's own token for curated and local patterns. */
        const val TOKEN_PREFIX = "javaApp.jdk.token."

        const val LOCAL_PATH = "javaApp.jdk.localPath"
        const val LOCAL_PATTERN = "javaApp.jdk.localPattern"

        /** A directory or an archive; it wins over every other source for that feature release and target. */
        fun pathProperty(feature: Int, target: Target) = "$VERSION_PREFIX$feature.${target.id}.path"

        fun localJdk(providers: ProviderFactory, feature: Int, target: Target): File? =
            providers.setting(pathProperty(feature, target))?.let(::File)

        fun from(providers: ProviderFactory): JdkSources {
            val distribution = providers.setting(DISTRIBUTION)?.let(JdkDistribution::parse) ?: JdkDistribution.DEFAULT
            return JdkSources(distribution, location(providers), yum(providers, distribution))
        }

        private fun yum(providers: ProviderFactory, distribution: JdkDistribution): JdkYumSource? {
            val repo = providers.setting(YUM_REPO) ?: return null
            val url = if (Regex("^(https?|file):").containsMatchIn(repo)) {
                URI.create(repo.trimEnd('/') + "/")
            } else {
                BinaryRepository.from(providers)?.repo(repo) ?: throw GradleException(
                    "$YUM_REPO=$repo names a repository beside the binary repository, but none is configured: " +
                        "set binaryRepo.repository or binaryRepo.url, or give $YUM_REPO as a full URL",
                )
            }
            val name = providers.setting(YUM_PACKAGE) ?: distribution.yumPackage ?: throw GradleException(
                "$YUM_REPO is set but not $YUM_PACKAGE, the JDK package's name in it " +
                    "(e.g. java-[feature]-openjdk-devel)",
            )
            return JdkYumSource(url, name, providers.setting(REVISION_FORMAT))
        }

        private fun location(providers: ProviderFactory): JdkLocation {
            val localPath = providers.setting(LOCAL_PATH)
            val binaryRepo = BinaryRepository.from(providers)
            val revision = providers.setting(REVISION_FORMAT)
            val tokens = CustomTokens.parse(providers.settingsPrefixedBy(TOKEN_PREFIX), TOKEN_PREFIX)
            return when {
                localPath != null -> JdkLocation.local(File(localPath))
                    .copy(pattern = providers.setting(LOCAL_PATTERN), revisionFormat = revision, customTokens = tokens)

                binaryRepo != null -> JdkLocation.binaryRepo(binaryRepo, providers.setting(BINARY_REPO_NAME))
                    .copy(
                        pattern = providers.setting(BINARY_REPO_PATTERN),
                        revisionFormat = revision,
                        customTokens = tokens,
                    )

                else -> JdkLocation.Public(providers.setting(PUBLIC_URL), providers.setting(PUBLIC_PATTERN))
            }
        }
    }
}

sealed interface JdkLocation {
    /** The vendor's own site, or a mirror of it: the vendor's file names, under an optional URL and pattern. */
    data class Public(val url: String? = null, val pattern: String? = null) : JdkLocation

    /** Archives renamed to the curated layout: the binary repository's runtimes repository, or a local directory. */
    data class Curated(
        val name: String,
        val baseUrl: URI,
        val pattern: String? = null,
        /** How `[revision]` spells the version; see [JdkSources.REVISION_FORMAT]. */
        val revisionFormat: String? = null,
        val customTokens: CustomTokens = CustomTokens(emptyMap()),
    ) : JdkLocation {
        fun revision(version: JdkVersion): String = revisionFormat?.let(version::format) ?: version.underscored

        fun tokens(version: JdkVersion, target: Target): Map<String, String> =
            version.tokens + customTokens.forTarget(target)
    }

    companion object {
        fun binaryRepo(binaryRepo: BinaryRepository, repository: String? = null) =
            Curated("binaryRepoJdks", repository?.let(binaryRepo::repo) ?: binaryRepo.runtimesUrl)

        fun local(directory: File) =
            Curated("localJdks", RuntimeRepository.slash(directory.absoluteFile.toURI().toString()))
    }
}

/** The version is spelled by [versionFormat], or else is the pin without its build (`21.0.5`). */
data class JdkYumSource(val repository: URI, val packageName: String, val versionFormat: String? = null) {
    fun packageName(feature: Int): String = packageName.replace("[feature]", feature.toString())

    fun version(version: JdkVersion): String = versionFormat?.let(version::format) ?: version.value.substringBefore('+')
}
