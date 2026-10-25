package io.github.fiftieshousewife.nodeapp

import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.util.Properties

/**
 * The approved Node.js version, shipped inside the plugin jar (written from `gradle/libs.versions.toml`).
 * Releasing a new plugin version is how a Node bump reaches every repository; `nodeApp.node.version`
 * overrides it for an emergency patch.
 */
object NodeCatalog {
    private const val RESOURCE = "/io/github/fiftieshousewife/nodeapp/node.properties"

    val cataloguedNode: String by lazy {
        Properties()
            .apply {
                NodeCatalog::class.java.getResourceAsStream(RESOURCE)?.use(::load)
                    ?: error("Missing $RESOURCE in plugin jar")
            }.getProperty("node.default")
    }

    fun nodeVersion(providers: ProviderFactory): Provider<String> =
        providers.gradleProperty(NodeSources.VERSION).orElse(cataloguedNode)
}
