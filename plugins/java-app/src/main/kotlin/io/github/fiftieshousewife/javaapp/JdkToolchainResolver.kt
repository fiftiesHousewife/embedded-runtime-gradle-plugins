package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.provider.ProviderFactory
import org.gradle.jvm.toolchain.JavaToolchainDownload
import org.gradle.jvm.toolchain.JavaToolchainRequest
import org.gradle.jvm.toolchain.JavaToolchainResolver
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.platform.Architecture
import org.gradle.platform.OperatingSystem
import java.io.File
import java.net.URI
import java.util.Optional
import javax.inject.Inject

/**
 * Compile JDKs from the same source and exact pin as the embedded runtime; a request carries only the feature
 * release. Other vendors fall through to other resolvers rather than being silently redirected.
 */
abstract class JdkToolchainResolver : JavaToolchainResolver {
    @get:Inject
    abstract val providers: ProviderFactory

    override fun resolve(request: JavaToolchainRequest): Optional<JavaToolchainDownload> =
        Optional.ofNullable(downloadUri(request)).map(JavaToolchainDownload::fromUri)

    private fun downloadUri(request: JavaToolchainRequest): URI? {
        val sources = JdkSources.from(providers)
        val spec = request.javaToolchainSpec
        val target = ToolchainRequests.targetOf(
            request.buildPlatform.operatingSystem,
            request.buildPlatform.architecture,
        )
        val servable = ToolchainRequests.serves(sources.distribution, spec.vendor.get(), spec.implementation.get())
        val feature = spec.languageVersion.get().asInt()
        val version = JdkCatalog.pinnedJdk(providers, sources.distribution, feature).takeIf { servable }
        if (version == null || target == null) return null
        return ToolchainRequests.uri(JdkSources.localJdk(providers, version.feature, target)) {
            sources.downloadUri(version, target).takeUnless { sources.archivesAreRpms }
        }
    }
}

internal object ToolchainRequests {
    fun serves(distribution: JdkDistribution, vendor: JvmVendorSpec, implementation: JvmImplementation) =
        implementation != JvmImplementation.J9 && distribution.vendorSpec.matches(vendor.toString())

    fun targetOf(operatingSystem: OperatingSystem, architecture: Architecture): Target? {
        val os = OS[operatingSystem]
        val arch = ARCH[architecture]
        return if (os != null && arch != null) Target(os, arch) else null
    }

    /**
     * Gradle provisions toolchains from zip and tar.gz only, so a directory or an RPM is left to the JDKs Gradle
     * finds on the machine (installed ones and `org.gradle.java.installations.paths`).
     */
    fun uri(local: File?, download: () -> URI?): URI? = when {
        local == null -> download()
        local.isFile && !local.name.endsWith(".rpm", ignoreCase = true) -> local.toURI()
        else -> null
    }

    private val OS = mapOf(
        OperatingSystem.LINUX to Os.LINUX,
        OperatingSystem.WINDOWS to Os.WINDOWS,
        OperatingSystem.MAC_OS to Os.MAC,
    )
    private val ARCH = mapOf(Architecture.X86_64 to Arch.X64, Architecture.AARCH64 to Arch.AARCH64)
}
