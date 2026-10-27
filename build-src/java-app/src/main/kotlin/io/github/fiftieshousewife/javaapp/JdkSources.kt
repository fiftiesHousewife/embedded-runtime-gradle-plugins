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

/**
 * Where JDK archives come from, first match wins: a JDK configured by path for one feature release
 * and target ([localJdk]); a shared local directory ([LOCAL_PATH]); the curated binary repository tree;
 * the chosen [distribution]'s own site.
 *
 * The compile toolchain resolver and the archive's runtime both ask this class, so compilation and
 * packaging can never disagree about where a JDK comes from.
 */
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

        // Renamed on upload, so the vendor's own naming is irrelevant here; the revision is spelled
        // as the tree names it.
        is JdkLocation.Curated ->
            repository.curated("jdk", location.revision(version), target, location.tokens(version, target))
    }

    /**
     * Whether the JDK archives here are RPMs: the pattern names its files `….rpm`. Gradle can't
     * provision a compile toolchain from an RPM, so the toolchain resolver leaves those to the JDKs
     * installed on the machine, and the plugin extracts the runtime itself.
     */
    val archivesAreRpms: Boolean get() = yum != null || repository.artifactPattern.endsWith(".rpm", ignoreCase = true)

    /**
     * The separate `jmods/` archive for a release whose JDK lacks it ([JdkDistribution.separateJmods]).
     * In a curated tree it sits beside the JDK as `jmods`:
     * `temurin/jmods/25.0.4.1_1/jmods-25.0.4.1_1-linux-x64.tar.gz`.
     */
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
        /** Which JDK vendor to compile with and embed: see [JdkDistribution]. */
        const val DISTRIBUTION = "javaApp.jdk.distribution"

        /** `javaApp.jdk.21=21.0.13+9` pins (or overrides the catalogue pin for) a feature release. */
        const val VERSION_PREFIX = "javaApp.jdk."

        /**
         * Where JDK archives live without a binary repository, and how they are laid out under that root.
         * Together these reach any tree — a mirror, an internal build server — without a plugin release.
         */
        const val PUBLIC_URL = "javaApp.jdk.url"
        const val PUBLIC_PATTERN = "javaApp.jdk.pattern"

        /** Ivy pattern for the curated binary repository tree, when it cannot use the default layout. */
        const val BINARY_REPO_PATTERN = "javaApp.jdk.binaryRepoPattern"

        /** The binary repository's repository holding JDKs, when it isn't `binaryRepo.repo.runtimes`: `yum-local`. */
        const val BINARY_REPO_NAME = "javaApp.jdk.binaryRepo"

        /**
         * How a curated pattern's `[revision]` spells the version, from [JdkVersion.format]'s tokens:
         * `[feature]_[update]` names `jdk21_5…` for 21.0.5. Default `[underscored]`.
         */
        const val REVISION_FORMAT = "javaApp.jdk.revisionFormat"

        /**
         * A YUM repository holding the JDK as an RPM package, found by name, version and CPU in the
         * repository's own metadata: a repository beside the binary repository's (`yum-local`, or a
         * path in it, `yum-local/rhel/9`), or a full `https:` / `file:` URL.
         */
        const val YUM_REPO = "javaApp.jdk.yumRepo"

        /** The package's name in [YUM_REPO]; `[feature]` is the Java release. Oracle's: `jdk-[feature]`. */
        const val YUM_PACKAGE = "javaApp.jdk.yumPackage"

        /**
         * Use the JDK running the build for this machine's target when it is exactly the pinned
         * distribution and version, instead of downloading another copy. Default true.
         */
        const val REUSE_RUNNING = "javaApp.jdk.reuseRunning"

        /**
         * `javaApp.jdk.token.<name>.<target or os>=<value>`: a token of your own for curated and local
         * patterns, e.g. `javaApp.jdk.token.plat.linux=l64` and `….windows=w64` for `[plat]`.
         */
        const val TOKEN_PREFIX = "javaApp.jdk.token."

        /** An absolute path to a shared directory of JDK archives, laid out like the curated binary repository tree. */
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

        /** First match wins: a shared local directory, then the binary repository, then the vendor's own site. */
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
        /** How `[revision]` spells the version; see [JdkSources.REVISION_FORMAT]. */
        val revisionFormat: String? = null,
        /** The organisation's own pattern tokens; see [JdkSources.TOKEN_PREFIX]. */
        val customTokens: CustomTokens = CustomTokens(emptyMap()),
    ) : JdkLocation {
        fun revision(version: JdkVersion): String = revisionFormat?.let(version::format) ?: version.underscored

        /** What the pattern may use besides Ivy's and the target's: the version's tokens, and the organisation's. */
        fun tokens(version: JdkVersion, target: Target): Map<String, String> =
            version.tokens + customTokens.forTarget(target)
    }

    companion object {
        /** The binary repository's runtimes repository, or [repository] beside it; `copy(pattern = …)` names files. */
        fun binaryRepo(binaryRepo: BinaryRepository, repository: String? = null) =
            Curated("binaryRepoJdks", repository?.let(binaryRepo::repo) ?: binaryRepo.runtimesUrl)

        /** A shared directory laid out like the curated tree; name files with `copy(pattern = …)`. */
        fun local(directory: File) =
            Curated("localJdks", RuntimeRepository.slash(directory.absoluteFile.toURI().toString()))
    }
}

/**
 * A YUM repository holding the JDK as an RPM: the package [packageName] (`[feature]` filled in) at
 * the pinned version, spelled by [versionFormat] or else the pin without its build (`21.0.5`).
 */
data class JdkYumSource(val repository: URI, val packageName: String, val versionFormat: String? = null) {
    fun packageName(feature: Int): String = packageName.replace("[feature]", feature.toString())

    fun version(version: JdkVersion): String = versionFormat?.let(version::format) ?: version.value.substringBefore('+')
}
