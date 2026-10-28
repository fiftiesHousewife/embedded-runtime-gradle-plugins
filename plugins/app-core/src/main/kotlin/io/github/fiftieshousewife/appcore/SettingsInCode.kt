package io.github.fiftieshousewife.appcore

import org.gradle.api.provider.ProviderFactory

/**
 * A setting from `settings.gradle.kts`'s `embeddedRuntime {}` block, else the Gradle property of that
 * name. Blank counts as unset, so `-Pname=` clears a machine-wide setting.
 */
fun ProviderFactory.setting(name: String): String? =
    (SettingsInCode[name] ?: gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }

/**
 * What `embeddedRuntime {}` set, recorded once the settings script has run: the JDK toolchain resolver,
 * which Gradle creates itself, can't see the settings. A daemon runs one build at a time.
 */
internal object SettingsInCode {
    @Volatile
    private var values: Map<String, String> = emptyMap()

    operator fun get(name: String): String? = values[name]

    fun reset() {
        values = emptyMap()
    }

    fun add(more: Map<String, String>) {
        values = values + more
    }
}
