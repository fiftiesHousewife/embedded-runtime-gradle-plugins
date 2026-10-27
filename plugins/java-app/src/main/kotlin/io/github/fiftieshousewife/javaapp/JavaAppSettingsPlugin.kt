package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppSettings
import io.github.fiftieshousewife.appcore.BinaryRepository
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.ExtensionAware
import org.gradle.jvm.toolchain.JavaToolchainResolverRegistry
import org.gradle.jvm.toolchain.JvmToolchainManagement
import javax.inject.Inject

/**
 * The shared repositories ([AppSettings]) plus compile JDK toolchains ([JdkToolchainResolver]) and the runtime JDKs'
 * Ivy repository ([JdkSources]), which read the same pin so the compiler and the runtime always agree.
 */
abstract class JavaAppSettingsPlugin
@Inject
constructor(
    private val toolchainResolvers: JavaToolchainResolverRegistry,
) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        AppSettings.alignPluginVersions(settings, PROJECT_PLUGIN_ID)
        settings.plugins.apply("jvm-toolchain-management")
        toolchainResolvers.register(JdkToolchainResolver::class.java)
        AppSettings.withBinaryRepository(settings) { binaryRepo ->
            AppSettings.configureRepositories(settings, binaryRepo)
            AppSettings.addRuntimeRepository(settings, JdkSources.from(settings.providers).repository, binaryRepo)
            addToolchainRepository(settings, binaryRepo)
        }
        AppSettings.markApplied(settings, ID)
    }

    private fun addToolchainRepository(settings: Settings, binaryRepo: BinaryRepository?) {
        val jvm = (settings.toolchainManagement as ExtensionAware).extensions.getByType(
            JvmToolchainManagement::class.java,
        )
        // Configured by URL, it is named `binaryRepo` so Gradle supplies binaryRepoUsername/Password by
        // convention; a borrowed repository's login is copied in.
        val byIdentity = binaryRepo != null && binaryRepo.borrowedFrom == null
        jvm.javaRepositories.repository(if (byIdentity) BinaryRepository.CREDENTIALS_NAME else "javaAppJdks") {
            resolverClass.set(JdkToolchainResolver::class.java)
            when {
                byIdentity -> AppSettings.authenticate(this)

                binaryRepo != null -> AppSettings.authenticateExplicitly(
                    this,
                    binaryRepo.login(settings.providers).orNull,
                )
            }
        }
    }

    companion object {
        const val ID = "io.github.fiftieshousewife.java-app.settings"
        const val PROJECT_PLUGIN_ID = "io.github.fiftieshousewife.java-app"
    }
}
