package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.CustomTokens
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import io.github.fiftieshousewife.appcore.settingsPrefixedBy
import org.gradle.api.provider.ProviderFactory
import java.net.URI

/** nodejs.org and the public npm registry, or the binary repository's curated tree and npm repository. */
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
                RuntimeRepository.curated(
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
        repository.curated(
            "node",
            version,
            target,
            mapOf("version" to version) + overrides.customTokens.forTarget(target),
        )
    }

    val npmRegistry: URI
        get() = binaryRepo?.repo(overrides.binaryRepoNpm) ?: RuntimeRepository.slash(overrides.publicNpmRegistry)

    companion object {
        const val GROUP = "nodejs"

        const val DEFAULT_URL = "https://nodejs.org/dist"

        /** nodejs.org/dist layout. */
        const val DEFAULT_PATTERN = "v[revision]/[artifact]-v[revision]-[classifier].[ext]"
        const val DEFAULT_NPM_REGISTRY = "https://registry.npmjs.org/"
        const val DEFAULT_BINARY_REPO_NPM = "api/npm/npm-virtual"

        /** `nodeApp.node.version=24.22.1` overrides the catalogue pin. */
        const val VERSION = "nodeApp.node.version"

        const val PUBLIC_URL = "nodeApp.node.url"
        const val PUBLIC_PATTERN = "nodeApp.node.pattern"

        const val BINARY_REPO_PATTERN = "nodeApp.node.binaryRepoPattern"

        const val NPM_REGISTRY = "nodeApp.npm.registry"
        const val BINARY_REPO_NPM = "binaryRepo.repo.npm"

        /** `nodeApp.node.token.<name>.<target or os>=<value>`: a pattern token of your own. */
        const val TOKEN_PREFIX = "nodeApp.node.token."

        /** npm does not use the JVM trust store, so it needs the corporate CAs as a PEM bundle of their own. */
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
                customTokens = CustomTokens.parse(providers.settingsPrefixedBy(TOKEN_PREFIX), TOKEN_PREFIX),
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

data class NodeOverrides(
    val publicUrl: String = NodeSources.DEFAULT_URL,
    val publicPattern: String? = null,
    val binaryRepoPattern: String? = null,
    val publicNpmRegistry: String = NodeSources.DEFAULT_NPM_REGISTRY,
    val binaryRepoNpm: String = NodeSources.DEFAULT_BINARY_REPO_NPM,
    /** The organisation's own tokens for [binaryRepoPattern]; see [NodeSources.TOKEN_PREFIX]. */
    val customTokens: CustomTokens = CustomTokens(emptyMap()),
)
