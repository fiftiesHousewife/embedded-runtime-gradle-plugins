package io.github.fiftieshousewife.platform.runtime

import io.github.fiftieshousewife.platform.PlatformRepositories
import io.github.fiftieshousewife.platform.RepositoryMode
import java.net.URI

/**
 * An Ivy-layout repository over a plain HTTP file tree. Runtime archives are resolved as ordinary
 * Gradle dependencies, so they get Gradle's cache, credentials, offline mode and dependency
 * verification (`gradle/verification-metadata.xml`) for free.
 */
data class RuntimeRepository(
    val name: String,
    val baseUrl: URI,
    val artifactPattern: String,
    val groups: List<String>,
)

/** One runtime archive, expressed as an Ivy module plus an explicit artifact. */
data class RuntimeArtifact(
    val group: String,
    val module: String,
    val revision: String,
    val artifact: String,
    val classifier: String?,
    val extension: String,
) {
    val notation: String get() = "$group:$module:$revision"
}

/**
 * The single place that knows how runtime archives are laid out in each source.
 *
 * Curated Artifactory layout (one generic repository, files renamed on upload):
 * ```
 * runtimes/temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz
 * runtimes/nodejs/node/24.18.1/node-24.18.1-linux-x64.tar.gz
 * ```
 * i.e. `[organisation]/[module]/[revision]/[artifact]-[revision]-[classifier].[ext]`.
 * Public layout: Adoptium GitHub releases and nodejs.org/dist, with their own naming.
 *
 * Both the Ivy repositories and the JVM toolchain resolver derive URLs from here, so compilation
 * and packaging can never disagree about where a JDK comes from.
 */
object RuntimeCoordinates {
    /** Ivy organisation of the Node.js archives; JDK organisations come from [JdkDistribution.group]. */
    const val NODE_GROUP = "nodejs"

    /**
     * Default curated (Artifactory) layout: one generic repository with files renamed on upload, so
     * every runtime — whatever vendor it came from — sits at a predictable path. Override per
     * repository with `platform.artifactory.runtimes.jdkPattern` / `.nodePattern` when an existing
     * Artifactory tree cannot be reshaped (for example a remote proxy that mirrors a vendor's own
     * layout verbatim: point the pattern at that layout instead).
     */
    const val CURATED_PATTERN = "[organisation]/[module]/[revision]/[artifact]-[revision]-[classifier].[ext]"

    /** Default nodejs.org/dist layout. */
    const val PUBLIC_NODE_PATTERN = "v[revision]/[artifact]-v[revision]-[classifier].[ext]"

    fun repositories(repos: PlatformRepositories): List<RuntimeRepository> {
        val jdkGroup = repos.jdkDistribution.group
        return when (repos.mode) {
            RepositoryMode.ARTIFACTORY -> {
                val artifactory = repos.artifactory!!
                val base = artifactory.repo(artifactory.runtimesRepo)
                // Two repositories over the same base when the patterns differ, one when they agree:
                // Gradle needs a repository per pattern, and exclusiveContent per group keeps each
                // lookup pointed at exactly one of them.
                val jdkPattern = repos.curatedJdkPattern
                val nodePattern = repos.curatedNodePattern
                if (jdkPattern == nodePattern) {
                    listOf(RuntimeRepository("artifactoryRuntimes", base, jdkPattern, listOf(jdkGroup, NODE_GROUP)))
                } else {
                    listOf(
                        RuntimeRepository("artifactoryJdks", base, jdkPattern, listOf(jdkGroup)),
                        RuntimeRepository("artifactoryNode", base, nodePattern, listOf(NODE_GROUP)),
                    )
                }
            }

            RepositoryMode.PUBLIC -> {
                listOf(
                    RuntimeRepository(
                        "${repos.jdkDistribution.id}Jdks",
                        slash(repos.publicJdkUrl),
                        repos.publicJdkPattern,
                        listOf(jdkGroup),
                    ),
                    RuntimeRepository("nodejsDist", slash(repos.public.nodeUrl), repos.publicNodePattern, listOf(NODE_GROUP)),
                )
            }
        }
    }

    fun jdk(
        repos: PlatformRepositories,
        version: JdkVersion,
        platform: Platform,
    ): RuntimeArtifact =
        when (repos.mode) {
            // Curated: renamed on upload, so the vendor's own naming is irrelevant here.
            RepositoryMode.ARTIFACTORY -> {
                RuntimeArtifact(
                    group = repos.jdkDistribution.group,
                    module = "jdk",
                    revision = version.underscored,
                    artifact = "jdk",
                    classifier = platform.id,
                    extension = platform.archiveExtension,
                )
            }

            RepositoryMode.PUBLIC -> {
                repos.jdkDistribution.artifact(version, platform)
            }
        }

    fun node(
        repos: PlatformRepositories,
        version: String,
        platform: Platform,
    ): RuntimeArtifact =
        RuntimeArtifact(
            group = NODE_GROUP,
            module = "node",
            revision = version,
            artifact = "node",
            // Curated: renamed on upload to the bundle's own platform ids, as for JDKs.
            classifier = if (repos.mode == RepositoryMode.ARTIFACTORY) platform.id else nodeDistClassifier(platform),
            extension = platform.archiveExtension,
        )

    /** The URL Ivy resolves for [artifact]; used by the toolchain resolver. */
    fun downloadUri(
        repos: PlatformRepositories,
        artifact: RuntimeArtifact,
    ): URI {
        val repo = repositories(repos).single { artifact.group in it.groups }
        val path =
            repo.artifactPattern
                .replace("[organisation]", artifact.group)
                .replace("[module]", artifact.module)
                .replace("[revision]", artifact.revision)
                .replace("[artifact]", artifact.artifact)
                .replace("[classifier]", artifact.classifier.orEmpty())
                .replace("[ext]", artifact.extension)
        return repo.baseUrl.resolve(path.replace("+", "%2B"))
    }

    /** nodejs.org names platforms `linux-x64`, `win-x64`, `darwin-arm64`. */
    fun nodeDistClassifier(platform: Platform): String {
        val os =
            when (platform.os) {
                Os.LINUX -> "linux"
                Os.WINDOWS -> "win"
                Os.MAC -> "darwin"
            }
        return "$os-${npmCpu(platform)}"
    }

    /** npm's `--os` / `--cpu` values, used to install optional dependencies for another platform. */
    fun npmOs(platform: Platform) =
        when (platform.os) {
            Os.LINUX -> "linux"
            Os.WINDOWS -> "win32"
            Os.MAC -> "darwin"
        }

    fun npmCpu(platform: Platform) =
        when (platform.arch) {
            Arch.X64 -> "x64"
            Arch.AARCH64 -> "arm64"
        }

    private fun slash(url: String) = URI.create(url.trimEnd('/') + "/")
}
