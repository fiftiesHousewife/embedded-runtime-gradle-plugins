package io.github.fiftieshousewife.platform

import java.util.Properties

/** The version of this plugin jar, written into a resource at build time. */
object PluginVersion {
    val current: String by lazy {
        Properties()
            .apply {
                PluginVersion::class.java.getResourceAsStream("/io/github/fiftieshousewife/platform/plugin.properties")?.use(::load)
            }.getProperty("version") ?: error("plugin.properties missing from plugin jar")
    }
}
