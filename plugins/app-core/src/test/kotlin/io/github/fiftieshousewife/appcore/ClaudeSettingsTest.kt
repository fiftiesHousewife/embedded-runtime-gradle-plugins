package io.github.fiftieshousewife.appcore

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClaudeSettingsTest {
    private fun parse(json: String?) = JsonSlurper().parseText(json) as Map<*, *>

    @Test
    fun `a project without settings gets the marketplace and the plugin`() {
        val settings = parse(ClaudeSettings.withPlugin(null))
        val marketplace = (settings["extraKnownMarketplaces"] as Map<*, *>)["embedded-runtime-gradle-plugins"]
        assertAll(
            {
                assertEquals(
                    mapOf(
                        "source" to
                            mapOf("source" to "github", "repo" to "fiftiesHousewife/embedded-runtime-gradle-plugins"),
                    ),
                    marketplace,
                )
            },
            {
                assertEquals(
                    mapOf("embedded-runtime@embedded-runtime-gradle-plugins" to true),
                    settings["enabledPlugins"],
                )
            },
        )
    }

    @Test
    fun `the project's own settings and plugins are kept`() {
        val existing = """{"permissions": {"allow": ["Bash(git *)"]}, "enabledPlugins": {"formatter@tools": true}}"""
        val settings = parse(ClaudeSettings.withPlugin(existing))
        assertAll(
            { assertEquals(mapOf("allow" to listOf("Bash(git *)")), settings["permissions"]) },
            {
                assertEquals(
                    mapOf("embedded-runtime@embedded-runtime-gradle-plugins" to true, "formatter@tools" to true),
                    settings["enabledPlugins"],
                )
            },
        )
    }

    @Test
    fun `settings that already name the plugin, even turned off, are left as they are`() {
        val installed = ClaudeSettings.withPlugin(null)
        val turnedOff = installed!!.replace(
            "\"embedded-runtime@embedded-runtime-gradle-plugins\": true",
            "\"embedded-runtime@embedded-runtime-gradle-plugins\": false",
        )
        assertAll(
            { assertNull(ClaudeSettings.withPlugin(installed)) },
            { assertNull(ClaudeSettings.withPlugin(turnedOff)) },
        )
    }
}
