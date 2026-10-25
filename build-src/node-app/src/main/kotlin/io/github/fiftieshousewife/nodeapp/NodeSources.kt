package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.Artifactory
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.provider.ProviderFactory
import java.net.URI

/**
 * Where Node.js archives and npm packages come from: nodejs.org and the public npm registry, or the
 * curated Artifactory tree and its npm repository when one is configured.
 */
class NodeSources(
    private val artifactory: Artifactory?,
    private val publicUrl: String = DEFAULT_URL,
    private val publicPattern: String? = null,
    /** Override of [RuntimeRepository.CURATED_PATTERN]; null means the default curated layout. */
    private val artifactoryPattern: String? = null,
    private val publicNpmRegistry: String = DEFAULT_NPM_REGISTRY,
    private val artifactoryNpmRepo: String = DEFAULT_ARTIFACTORY_NPM_REPO,
) {
    val repository: RuntimeRepository
        get() =
            if (artifactory == null) {
                RuntimeRepository("nodejsDist", RuntimeRepository.slash(publicUrl), publicPattern ?: DEFAULT_PATTERN, GROUP)
            } else {
                RuntimeRepository(
                    "artifactoryNode",
                    artifactory.runtimesUrl,
                    artifactoryPattern ?: RuntimeRepository.CURATED_PATTERN,
                    GROUP,
                )
            }

    fun artifact(
        version: String,
        target: Target,
    ): RuntimeArtifact =
        if (artifactory == null) {
            RuntimeArtifact(GROUP, "node", version, "node", distClassifier(target), target.archiveExtension)
        } else {
            // Renamed on upload to the archive's own target ids, as for JDKs.
            RuntimeArtifact.curated(GROUP, "node", version, target)
        }

    val npmRegistry: URI
        get() = artifactory?.repo(artifactoryNpmRepo) ?: RuntimeRepository.slash(publicNpmRegistry)

    val usesArtifactory: Boolean get() = artifactory != null

    companion object {
        /** Ivy organisation of the Node.js archives. */
        const val GROUP = "nodejs"

        const val DEFAULT_URL = "https://nodejs.org/dist"

        /** nodejs.org/dist layout. */
        const val DEFAULT_PATTERN = "v[revision]/[artifact]-v[revision]-[classifier].[ext]"
        const val DEFAULT_NPM_REGISTRY = "https://registry.npmjs.org/"
        const val DEFAULT_ARTIFACTORY_NPM_REPO = "api/npm/npm-virtual"

        /** `nodeApp.node.version=24.18.1` overrides the catalogue pin. */
        const val VERSION = "nodeApp.node.version"

        /** Where Node archives live without Artifactory, and how they are laid out under that root. */
        const val PUBLIC_URL = "nodeApp.node.url"
        const val PUBLIC_PATTERN = "nodeApp.node.pattern"

        /** Ivy pattern for the curated Artifactory tree, when it cannot use the default layout. */
        const val ARTIFACTORY_PATTERN = "nodeApp.node.artifactoryPattern"

        const val NPM_REGISTRY = "nodeApp.npm.registry"
        const val ARTIFACTORY_NPM_REPO = "artifactory.repo.npm"

        /**
         * PEM bundle of corporate CAs for npm, which does not use the JVM trust store. Gradle itself
         * trusts whatever the JVM trust store trusts.
         */
        const val NPM_CA_FILE = "nodeApp.npm.caFile"

        fun from(providers: ProviderFactory) =
            NodeSources(
                artifactory = Artifactory.from(providers),
                publicUrl = providers.setting(PUBLIC_URL) ?: DEFAULT_URL,
                publicPattern = providers.setting(PUBLIC_PATTERN),
                artifactoryPattern = providers.setting(ARTIFACTORY_PATTERN),
                publicNpmRegistry = providers.setting(NPM_REGISTRY) ?: DEFAULT_NPM_REGISTRY,
                artifactoryNpmRepo = providers.setting(ARTIFACTORY_NPM_REPO) ?: DEFAULT_ARTIFACTORY_NPM_REPO,
            )

        /** nodejs.org names targets `linux-x64`, `win-x64`, `darwin-arm64`. */
        fun distClassifier(target: Target): String {
            val os =
                when (target.os) {
                    Os.LINUX -> "linux"
                    Os.WINDOWS -> "win"
                    Os.MAC -> "darwin"
                }
            return "$os-${npmCpu(target)}"
        }

        /** npm's `--os` / `--cpu` values, used to install optional dependencies for another target. */
        fun npmOs(target: Target) =
            when (target.os) {
                Os.LINUX -> "linux"
                Os.WINDOWS -> "win32"
                Os.MAC -> "darwin"
            }

        fun npmCpu(target: Target) =
            when (target.arch) {
                Arch.X64 -> "x64"
                Arch.AARCH64 -> "arm64"
            }
    }
}
