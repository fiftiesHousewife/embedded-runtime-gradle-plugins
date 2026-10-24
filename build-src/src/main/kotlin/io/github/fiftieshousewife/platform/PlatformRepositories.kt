package io.github.fiftieshousewife.platform

import io.github.fiftieshousewife.platform.runtime.JdkDistribution
import io.github.fiftieshousewife.platform.runtime.RuntimeCoordinates
import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import java.net.URI

/**
 * Where every external input to the build comes from: Maven libraries, Gradle plugins,
 * npm packages, JDK and Node runtime archives.
 *
 * The mode is machine configuration, never project configuration: set it once per machine or CI
 * image in `~/.gradle/gradle.properties` (or `ORG_GRADLE_PROJECT_...` environment variables) and
 * the same project builds unchanged against the public internet or a corporate Artifactory.
 */
enum class RepositoryMode {
    PUBLIC,
    ARTIFACTORY,
    ;

    companion object {
        fun parse(value: String): RepositoryMode =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw GradleException(
                    "Unsupported ${PlatformProperties.REPOSITORIES}='$value'. Use one of: " +
                        entries.joinToString { it.name.lowercase() },
                )
    }
}

/** Gradle property names understood by the platform plugins. */
object PlatformProperties {
    const val REPOSITORIES = "platform.repositories"

    const val ARTIFACTORY_URL = "platform.artifactory.url"
    const val ARTIFACTORY_MAVEN_REPO = "platform.artifactory.repo.maven"
    const val ARTIFACTORY_PLUGINS_REPO = "platform.artifactory.repo.gradlePlugins"
    const val ARTIFACTORY_NPM_REPO = "platform.artifactory.repo.npm"
    const val ARTIFACTORY_RUNTIMES_REPO = "platform.artifactory.repo.runtimes"
    const val ARTIFACTORY_RELEASES_REPO = "platform.artifactory.repo.releases"
    const val ARTIFACTORY_SNAPSHOTS_REPO = "platform.artifactory.repo.snapshots"
    const val ARTIFACTORY_ALLOW_INSECURE = "platform.artifactory.allowInsecureProtocol"

    /** Which JDK vendor to provision and bundle: see [io.github.fiftieshousewife.platform.runtime.JdkDistribution]. */
    const val JDK_DISTRIBUTION = "platform.runtime.jdk.distribution"

    /**
     * Where JDK archives live, and how they are laid out under that root. Together these reach any
     * tree — a vendor's own site, a mirror, an internal build server — without a plugin release.
     */
    const val PUBLIC_JDK_URL = "platform.public.jdkUrl"
    const val PUBLIC_JDK_PATTERN = "platform.public.jdkPattern"

    const val PUBLIC_NODE_URL = "platform.public.nodeUrl"
    const val PUBLIC_NODE_PATTERN = "platform.public.nodePattern"

    /** Ivy patterns for the curated Artifactory tree, when it cannot use the default layout. */
    const val ARTIFACTORY_JDK_PATTERN = "platform.artifactory.runtimes.jdkPattern"
    const val ARTIFACTORY_NODE_PATTERN = "platform.artifactory.runtimes.nodePattern"
    const val PUBLIC_NPM_REGISTRY = "platform.public.npmRegistry"

    /**
     * PEM bundle of corporate CAs for tools that do not use the JVM trust store (npm). Gradle
     * itself trusts whatever the JVM trust store trusts.
     */
    const val TLS_CA_FILE = "platform.tls.caFile"

    /** `platform.runtime.jdk.21=21.0.12.1+1` pins (or overrides the catalogue pin for) a JDK feature release. */
    const val RUNTIME_JDK_PREFIX = "platform.runtime.jdk."
    const val RUNTIME_NODE = "platform.runtime.node"

    const val TARGET_PLATFORM = "bundle.targetPlatform"
    const val ALLOW_UNTESTED_PUBLISH = "bundle.allowUntestedPublish"
    const val GIT_COMMIT = "bundle.gitCommit"

    /**
     * Name of the Gradle credentials identity. Gradle reads `artifactoryUsername` and
     * `artifactoryPassword` (an Artifactory identity or reference token) lazily, at execution time,
     * so secrets never land in the configuration cache.
     */
    const val CREDENTIALS_NAME = "artifactory"
}

data class ArtifactoryLayout(
    val baseUrl: String,
    val mavenRepo: String,
    val gradlePluginsRepo: String,
    val npmRepo: String,
    val runtimesRepo: String,
    val releasesRepo: String,
    val snapshotsRepo: String,
    val jdkPattern: String?,
    val nodePattern: String?,
    val allowInsecureProtocol: Boolean,
) {
    fun repo(path: String): URI = URI.create("${baseUrl.trimEnd('/')}/${path.trim('/')}/")
}

data class PublicSources(
    /** Null means "use the selected distribution's own default". */
    val jdkUrl: String?,
    val jdkPattern: String?,
    val nodeUrl: String,
    val nodePattern: String?,
    val npmRegistry: String,
)

class PlatformRepositories(
    val mode: RepositoryMode,
    val jdkDistribution: JdkDistribution,
    val artifactory: ArtifactoryLayout?,
    val public: PublicSources,
) {
    /** Base URL for JDK archives: the explicit override, else the distribution's own site. */
    val publicJdkUrl: String
        get() = public.jdkUrl ?: jdkDistribution.defaultBaseUrl

    val publicJdkPattern: String
        get() = public.jdkPattern ?: jdkDistribution.defaultPattern

    val publicNodePattern: String
        get() = public.nodePattern ?: RuntimeCoordinates.PUBLIC_NODE_PATTERN

    val curatedJdkPattern: String
        get() = artifactory?.jdkPattern ?: RuntimeCoordinates.CURATED_PATTERN

    val curatedNodePattern: String
        get() = artifactory?.nodePattern ?: RuntimeCoordinates.CURATED_PATTERN

    val mavenRepositoryUrl: URI?
        get() = artifactory?.repo(artifactory.mavenRepo)

    val gradlePluginsRepositoryUrl: URI?
        get() = artifactory?.repo(artifactory.gradlePluginsRepo)

    val npmRegistry: URI
        get() = artifactory?.repo(artifactory.npmRepo) ?: URI.create(public.npmRegistry.trimEnd('/') + "/")

    val allowInsecureProtocol: Boolean
        get() = artifactory?.allowInsecureProtocol ?: false

    fun publishRepositoryUrl(snapshot: Boolean): URI? = artifactory?.let { it.repo(if (snapshot) it.snapshotsRepo else it.releasesRepo) }

    companion object {
        fun from(providers: ProviderFactory): PlatformRepositories {
            val prop = { name: String -> providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() } }
            val mode = RepositoryMode.parse(prop(PlatformProperties.REPOSITORIES) ?: "public")
            return PlatformRepositories(
                mode = mode,
                jdkDistribution = prop(PlatformProperties.JDK_DISTRIBUTION)?.let(JdkDistribution::parse) ?: JdkDistribution.DEFAULT,
                artifactory = if (mode == RepositoryMode.ARTIFACTORY) artifactoryLayout(prop) else null,
                public = publicSources(prop),
            )
        }

        private fun publicSources(prop: (String) -> String?) =
            PublicSources(
                jdkUrl = prop(PlatformProperties.PUBLIC_JDK_URL),
                jdkPattern = prop(PlatformProperties.PUBLIC_JDK_PATTERN),
                nodeUrl = prop(PlatformProperties.PUBLIC_NODE_URL) ?: "https://nodejs.org/dist",
                nodePattern = prop(PlatformProperties.PUBLIC_NODE_PATTERN),
                npmRegistry = prop(PlatformProperties.PUBLIC_NPM_REGISTRY) ?: "https://registry.npmjs.org/",
            )

        private fun artifactoryLayout(prop: (String) -> String?): ArtifactoryLayout {
            val url =
                prop(PlatformProperties.ARTIFACTORY_URL) ?: throw GradleException(
                    "${PlatformProperties.REPOSITORIES}=artifactory requires ${PlatformProperties.ARTIFACTORY_URL}, " +
                        "e.g. https://artifactory.example.com/artifactory",
                )
            return ArtifactoryLayout(
                baseUrl = url,
                mavenRepo = prop(PlatformProperties.ARTIFACTORY_MAVEN_REPO) ?: "maven-virtual",
                gradlePluginsRepo = prop(PlatformProperties.ARTIFACTORY_PLUGINS_REPO) ?: "gradle-plugins-virtual",
                npmRepo = prop(PlatformProperties.ARTIFACTORY_NPM_REPO) ?: "api/npm/npm-virtual",
                runtimesRepo = prop(PlatformProperties.ARTIFACTORY_RUNTIMES_REPO) ?: "runtimes",
                releasesRepo = prop(PlatformProperties.ARTIFACTORY_RELEASES_REPO) ?: "libs-release-local",
                snapshotsRepo = prop(PlatformProperties.ARTIFACTORY_SNAPSHOTS_REPO) ?: "libs-snapshot-local",
                jdkPattern = prop(PlatformProperties.ARTIFACTORY_JDK_PATTERN),
                nodePattern = prop(PlatformProperties.ARTIFACTORY_NODE_PATTERN),
                allowInsecureProtocol = prop(PlatformProperties.ARTIFACTORY_ALLOW_INSECURE).toBoolean(),
            )
        }
    }
}
