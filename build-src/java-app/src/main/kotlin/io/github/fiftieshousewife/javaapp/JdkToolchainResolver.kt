package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Arch
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.provider.ProviderFactory
import org.gradle.jvm.toolchain.JavaToolchainDownload
import org.gradle.jvm.toolchain.JavaToolchainRequest
import org.gradle.jvm.toolchain.JavaToolchainResolver
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.platform.Architecture
import org.gradle.platform.OperatingSystem
import java.util.Optional
import javax.inject.Inject

/**
 * Provisions compile JDKs from the same source, and at the same exact version, as the runtime that
 * gets linked into the archive. Gradle toolchain requests carry only a feature version ("21"); the
 * exact patch level comes from the java-app catalogue or a `javaApp.jdk.<feature>` override.
 *
 * Only HotSpot builds of the configured `javaApp.jdk.distribution` are served. Requests for another
 * vendor or implementation fall through to any other configured resolver.
 */
abstract class JdkToolchainResolver : JavaToolchainResolver {
    @get:Inject
    abstract val providers: ProviderFactory

    override fun resolve(request: JavaToolchainRequest): Optional<JavaToolchainDownload> {
        val spec = request.javaToolchainSpec
        val sources = JdkSources.from(providers)
        // Serve only the distribution this machine is configured for; any other vendor request
        // falls through to Gradle's other resolvers rather than being silently redirected.
        val servable =
            spec.implementation.get() != JvmImplementation.J9 &&
                sources.distribution.vendorSpec.matches(spec.vendor.get().toString())
        val version =
            if (servable) JdkCatalog.pinnedJdk(providers, sources.distribution, spec.languageVersion.get().asInt()) else null
        val target = targetOf(request)
        if (version == null || target == null) return Optional.empty()

        // A JDK configured by path: Gradle provisions from archives only, so an installed directory
        // is left to Gradle's own detection (org.gradle.java.installations.paths).
        val local = JdkSources.localJdk(providers, version.feature, target)
        val uri =
            when {
                local == null -> sources.downloadUri(version, target)
                local.isFile -> local.toURI()
                else -> null
            }
        return Optional.ofNullable(uri).map(JavaToolchainDownload::fromUri)
    }

    private fun targetOf(request: JavaToolchainRequest): Target? {
        val os =
            when (request.buildPlatform.operatingSystem) {
                OperatingSystem.LINUX -> Os.LINUX
                OperatingSystem.WINDOWS -> Os.WINDOWS
                OperatingSystem.MAC_OS -> Os.MAC
                else -> null
            }
        val arch =
            when (request.buildPlatform.architecture) {
                Architecture.X86_64 -> Arch.X64
                Architecture.AARCH64 -> Arch.AARCH64
                else -> null
            }
        return if (os != null && arch != null) Target(os, arch) else null
    }
}
