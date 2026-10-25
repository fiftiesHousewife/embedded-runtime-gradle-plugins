package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.provider.ProviderFactory
import java.net.URI

/**
 * Where Node.js archives and npm packages come from: nodejs.org and the public npm registry, or the
 * curated BinaryRepository tree and its npm repository when one is configured.
 */
class NodeSources(private val binaryRepo: BinaryRepository?, private val overrides: NodeOverrides = NodeOverrides()) {
    val repository: RuntimeRepository
        get() =
            if (binaryRepo == null) {
                RuntimeRepository(
                    "nodejsDist",
                    RuntimeRepository.slash(overrides.publicUrl),
                    overrides.publicPattern ?: DEFAULT_PATTERN,
                    GROUP,
                )
            } else {
                RuntimeRepository(
                    "binaryRepoNode",
                    binaryRepo.runtimesUrl,
                    overrides.binaryRepoPattern ?: RuntimeRepository.CURATED_PATTERN,
                    GROUP,
                )
            }

    fun artifact(version: String, target: Target): RuntimeArtifact = if (binaryRepo == null) {
        RuntimeArtifact(GROUP, "node", version, "node", distClassifier(target), target.archiveExtension)
    } else {
        // Renamed on upload to the archive's own target ids, as for JDKs.
        RuntimeArtifact.curated(GROUP, "node", version, target)
    }

    val npmRegistry: URI
        get() = binaryRepo?.repo(overrides.binaryRepoNpm) ?: RuntimeRepository.slash(overrides.publicNpmRegistry)

    val usesBinaryRepo: Boolean get() = binaryRepo != null

    companion object {
        /** Ivy organisation of the Node.js archives. */
        const val GROUP = "nodejs"

        const val DEFAULT_URL = "https://nodejs.org/dist"

        /** nodejs.org/dist layout. */
        const val DEFAULT_PATTERN = "v[revision]/[artifact]-v[revision]-[classifier].[ext]"
        const val DEFAULT_NPM_REGISTRY = "https://registry.npmjs.org/"
        const val DEFAULT_BINARY_REPO_NPM = "api/npm/npm-virtual"

        /** `nodeApp.node.version=24.18.1` overrides the catalogue pin. */
        const val VERSION = "nodeApp.node.version"

        /** Where Node archives live without BinaryRepository, and how they are laid out under that root. */
        const val PUBLIC_URL = "nodeApp.node.url"
        const val PUBLIC_PATTERN = "nodeApp.node.pattern"

        /** Ivy pattern for the curated BinaryRepository tree, when it cannot use the default layout. */
        const val BINARY_REPO_PATTERN = "nodeApp.node.binaryRepoPattern"

        const val NPM_REGISTRY = "nodeApp.npm.registry"
        const val BINARY_REPO_NPM = "binaryRepo.repo.npm"

        /**
         * PEM bundle of corporate CAs for npm, which does not use the JVM trust store. Gradle itself
         * trusts whatever the JVM trust store trusts.
         */
        const val NPM_CA_FILE = "nodeApp.npm.caFile"

        fun from(providers: ProviderFactory) = NodeSources(
            binaryRepo = BinaryRepository.from(providers),
            overrides =
            NodeOverrides(
                publicUrl = providers.setting(PUBLIC_URL) ?: DEFAULT_URL,
                publicPattern = providers.setting(PUBLIC_PATTERN),
                binaryRepoPattern = providers.setting(BINARY_REPO_PATTERN),
                publicNpmRegistry = providers.setting(NPM_REGISTRY) ?: DEFAULT_NPM_REGISTRY,
                binaryRepoNpm = providers.setting(BINARY_REPO_NPM) ?: DEFAULT_BINARY_REPO_NPM,
            ),
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
        fun npmOs(target: Target) = when (target.os) {
            Os.LINUX -> "linux"
            Os.WINDOWS -> "win32"
            Os.MAC -> "darwin"
        }

        fun npmCpu(target: Target) = when (target.arch) {
            Arch.X64 -> "x64"
            Arch.AARCH64 -> "arm64"
        }
    }
}

/** Where Node.js and npm packages come from when a machine overrides the defaults. */
data class NodeOverrides(
    val publicUrl: String = NodeSources.DEFAULT_URL,
    val publicPattern: String? = null,
    /** Override of [RuntimeRepository.CURATED_PATTERN]; null means the default curated layout. */
    val binaryRepoPattern: String? = null,
    val publicNpmRegistry: String = NodeSources.DEFAULT_NPM_REGISTRY,
    val binaryRepoNpm: String = NodeSources.DEFAULT_BINARY_REPO_NPM,
)
