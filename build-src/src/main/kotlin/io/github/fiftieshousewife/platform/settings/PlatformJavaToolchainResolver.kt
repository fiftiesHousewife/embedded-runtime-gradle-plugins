package io.github.fiftieshousewife.platform.settings

import io.github.fiftieshousewife.platform.PlatformRepositories
import io.github.fiftieshousewife.platform.runtime.Arch
import io.github.fiftieshousewife.platform.runtime.Os
import io.github.fiftieshousewife.platform.runtime.Platform
import io.github.fiftieshousewife.platform.runtime.RuntimeCatalog
import io.github.fiftieshousewife.platform.runtime.RuntimeCoordinates
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
 * gets bundled. Gradle toolchain requests carry only a feature version ("21"); the exact patch level
 * comes from the platform catalogue or a `platform.runtime.jdk.<feature>` override.
 *
 * Only HotSpot builds of the configured `platform.runtime.jdk.distribution` are served. Requests for
 * another vendor or implementation fall through to any other configured resolver.
 */
abstract class PlatformJavaToolchainResolver : JavaToolchainResolver {
    @get:Inject
    abstract val providers: ProviderFactory

    override fun resolve(request: JavaToolchainRequest): Optional<JavaToolchainDownload> {
        val spec = request.javaToolchainSpec
        val repos = PlatformRepositories.from(providers)
        // Serve only the distribution this machine is configured for; any other vendor request
        // falls through to Gradle's other resolvers rather than being silently redirected.
        val servable =
            spec.implementation.get() != JvmImplementation.J9 &&
                repos.jdkDistribution.vendorSpec.matches(spec.vendor.get().toString())
        val version =
            if (servable) RuntimeCatalog.pinnedJdk(providers, repos.jdkDistribution, spec.languageVersion.get().asInt()) else null
        val platform = platformOf(request)
        if (version == null || platform == null) return Optional.empty()

        val artifact = RuntimeCoordinates.jdk(repos, version, platform)
        return Optional.of(JavaToolchainDownload.fromUri(RuntimeCoordinates.downloadUri(repos, artifact)))
    }

    private fun platformOf(request: JavaToolchainRequest): Platform? {
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
        return if (os != null && arch != null) Platform(os, arch) else null
    }
}
