package io.github.fiftieshousewife.appcore

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Claude Code takes skills only through a plugin, and offers one named in a project's committed
 * `.claude/settings.json` to everyone who trusts the project. A project that turned it off keeps it off.
 */
internal object ClaudeSettings {
    private const val MARKETPLACE = "embedded-runtime-gradle-plugins"
    private const val PLUGIN = "embedded-runtime@$MARKETPLACE"
    private const val MARKETPLACES = "extraKnownMarketplaces"
    private const val PLUGINS = "enabledPlugins"
    private val source = mapOf("source" to mapOf("source" to "github", "repo" to "fiftiesHousewife/$MARKETPLACE"))

    /** The settings with the plugin added, or null when they have it. */
    fun withPlugin(existing: String?): String? {
        val settings = existing?.takeIf(String::isNotBlank)?.let { JsonSlurper().parseText(it).asMap() }.orEmpty()
        val marketplaces = settings[MARKETPLACES].asMap()
        val plugins = settings[PLUGINS].asMap()
        if (MARKETPLACE in marketplaces && PLUGIN in plugins) return null
        val merged = settings +
            mapOf(
                MARKETPLACES to mapOf(MARKETPLACE to source) + marketplaces,
                PLUGINS to mapOf(PLUGIN to true) + plugins,
            )
        return JsonOutput.prettyPrint(JsonOutput.toJson(merged)) + "\n"
    }

    private fun Any?.asMap(): Map<String, Any?> =
        (this as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }.orEmpty()
}
