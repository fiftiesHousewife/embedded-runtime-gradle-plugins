package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.RuntimeRepository
import io.github.fiftieshousewife.appcore.RuntimeSource
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import java.net.URI

/** nodejs.org and the public npm registry, or the binary repository's curated tree and npm repository. */
class NodeSources(private val binaryRepo: BinaryRepository?, private val overrides: NodeOverrides = NodeOverrides()) {
    /** Where Node.js comes from: a mirror keeps nodejs.org's layout, anything else is curated. */
    private val curatedAt: URI? = when (val source = overrides.source) {
        is RuntimeSource.Mirror -> null

        is RuntimeSource.Directory -> source.uri

        is RuntimeSource.Repository -> requireBinaryRepo().repo(source.name)

        is RuntimeSource.Yum -> throw GradleException(
            "$SOURCE can't be a YUM repository: Node.js isn't packaged as an RPM",
        )

        RuntimeSource.Default -> binaryRepo?.runtimesUrl
    }

    val repository: RuntimeRepository
        get() = curatedAt?.let {
            val name = if (overrides.source is RuntimeSource.Directory) "localNode" else "binaryRepoNode"
            RuntimeRepository.curated(name, it, overrides.pattern ?: RuntimeRepository.CURATED_PATTERN, GROUP)
        } ?: RuntimeRepository(
            "nodejsDist",
            RuntimeRepository.slash((overrides.source as? RuntimeSource.Mirror)?.url ?: DEFAULT_URL),
            overrides.pattern ?: DEFAULT_PATTERN,
            GROUP,
        )

    fun artifact(version: String, target: Target): RuntimeArtifact = if (curatedAt == null) {
        RuntimeArtifact(GROUP, "node", version, "node", distClassifier(target), target.archiveExtension)
    } else {
        // Renamed on upload to the archive's own target ids, as for JDKs.
        repository.curated("node", version, target, mapOf("version" to version))
    }

    private fun requireBinaryRepo() = binaryRepo ?: throw GradleException(
        "$SOURCE names a repository beside the binary repository, but none is configured: " +
            "set binaryRepo.repository or binaryRepo.url, or give $SOURCE as a URL",
    )

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

        /** Where Node.js comes from ([RuntimeSource]); [PATTERN] names the files in it. */
        const val SOURCE = "nodeApp.node.source"
        const val PATTERN = "nodeApp.node.pattern"

        const val NPM_REGISTRY = "nodeApp.npm.registry"
        const val BINARY_REPO_NPM = "binaryRepo.repo.npm"

        /** npm does not use the JVM trust store, so it needs the corporate CAs as a PEM bundle of their own. */
        const val NPM_CA_FILE = "nodeApp.npm.caFile"

        fun from(providers: ProviderFactory) = NodeSources(
            binaryRepo = BinaryRepository.from(providers),
            overrides =
            NodeOverrides(
                source = RuntimeSource.parse(SOURCE, providers.setting(SOURCE)),
                pattern = providers.setting(PATTERN),
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

data class NodeOverrides(
    val source: RuntimeSource = RuntimeSource.Default,
    val pattern: String? = null,
    val publicNpmRegistry: String = NodeSources.DEFAULT_NPM_REGISTRY,
    val binaryRepoNpm: String = NodeSources.DEFAULT_BINARY_REPO_NPM,
)
