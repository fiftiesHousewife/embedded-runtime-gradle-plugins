package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppSettings
import io.github.fiftieshousewife.appcore.Artifactory
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.ExtensionAware
import org.gradle.jvm.toolchain.JavaToolchainResolverRegistry
import org.gradle.jvm.toolchain.JvmToolchainManagement
import javax.inject.Inject

/**
 * `io.github.fiftieshousewife.java-app.settings` — the Maven and plugin repositories every build
 * needs (see [AppSettings]), plus the two Java-specific ones:
 *
 * - compile JDK toolchains, provisioned by [JdkToolchainResolver] at the exact pinned version;
 * - an Ivy repository for the JDK archives linked into each app, from [JdkSources].
 *
 * Both read the same distribution, pin and source, so the compiler and the runtime always agree.
 */
abstract class JavaAppSettingsPlugin
@Inject
constructor(
    private val toolchainResolvers: JavaToolchainResolverRegistry,
) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val sources = JdkSources.from(settings.providers)
        val artifactory = Artifactory.from(settings.providers)

        AppSettings.configureRepositories(settings, artifactory)
        AppSettings.addRuntimeRepository(settings, sources.repository, artifactory)
        AppSettings.alignPluginVersions(settings, PROJECT_PLUGIN_ID)
        configureToolchains(settings, artifactory)
        AppSettings.markApplied(settings, ID)
    }

    private fun configureToolchains(settings: Settings, artifactory: Artifactory?) {
        settings.plugins.apply("jvm-toolchain-management")
        toolchainResolvers.register(JdkToolchainResolver::class.java)

        val jvm = (settings.toolchainManagement as ExtensionAware).extensions.getByType(
            JvmToolchainManagement::class.java,
        )
        // Named `artifactory` behind Artifactory so Gradle supplies the credentials by convention.
        jvm.javaRepositories.repository(if (artifactory != null) Artifactory.CREDENTIALS_NAME else "javaAppJdks") {
            resolverClass.set(JdkToolchainResolver::class.java)
            if (artifactory != null) AppSettings.authenticate(this)
        }
    }

    companion object {
        const val ID = "io.github.fiftieshousewife.java-app.settings"
        const val PROJECT_PLUGIN_ID = "io.github.fiftieshousewife.java-app"
    }
}
