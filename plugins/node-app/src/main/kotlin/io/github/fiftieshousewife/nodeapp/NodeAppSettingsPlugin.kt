package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppSettings
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * `io.github.fiftieshousewife.node-app.settings` — the Maven and plugin repositories every build
 * needs (see [AppSettings]), plus an Ivy repository for the Node.js archives, from [NodeSources].
 *
 * npm packages are not a Gradle repository: the npm tasks point npm at [NodeSources.npmRegistry]
 * through a generated user config instead.
 */
abstract class NodeAppSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        AppSettings.alignPluginVersions(settings, PROJECT_PLUGIN_ID)
        AppSettings.withBinaryRepository(settings) { binaryRepo ->
            AppSettings.configureRepositories(settings, binaryRepo)
            AppSettings.addRuntimeRepository(settings, NodeSources.from(settings.providers).repository, binaryRepo)
        }
        AppSettings.markApplied(settings, ID)
    }

    companion object {
        const val ID = "io.github.fiftieshousewife.node-app.settings"
        const val PROJECT_PLUGIN_ID = "io.github.fiftieshousewife.node-app"
    }
}
