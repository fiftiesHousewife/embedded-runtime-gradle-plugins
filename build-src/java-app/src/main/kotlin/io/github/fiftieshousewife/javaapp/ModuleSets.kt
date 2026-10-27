package io.github.fiftieshousewife.javaapp

import java.util.Properties

/**
 * Apps linking the same fixed set, JDK and target ship byte-identical runtimes, which registries and nodes store
 * and pull once; linking exactly each app's modules would give every app its own layer.
 */
internal object ModuleSets {
    const val ENABLED_PROPERTY = "javaApp.sharedModuleSets"

    private const val RESOURCE = "module-sets.properties"
    private const val SET = "set."
    private const val LAST_FEATURE = "lastFeature."

    private val catalogue: Map<String, String> by lazy {
        Properties().apply {
            ModuleSets::class.java.getResourceAsStream(RESOURCE)?.use(::load)
                ?: error("Missing $RESOURCE in plugin jar")
        }.let { properties -> properties.stringPropertyNames().associateWith(properties::getProperty) }
    }

    fun defaults(feature: Int): Map<String, List<String>> = parse(catalogue, feature)

    /** Drops modules retired before [feature]: a set naming a module the JDK no longer has would fail to link. */
    fun parse(entries: Map<String, String>, feature: Int): Map<String, List<String>> {
        val retired = entries
            .filterKeys { it.startsWith(LAST_FEATURE) }
            .filterValues { it.trim().toInt() < feature }
            .keys
            .map { it.removePrefix(LAST_FEATURE) }
            .toSet()
        return entries
            .filterKeys { it.startsWith(SET) }
            .map { (key, modules) -> key.removePrefix(SET) to split(modules).filterNot(retired::contains) }
            .toMap()
    }

    /** Ties go by name so declaration order never matters; with no set fitting, the app links alone, unshared. */
    fun choose(needed: List<String>, sets: Map<String, List<String>>, forbidden: List<String>): List<String> =
        sets.entries
            .filter { (_, modules) -> modules.containsAll(needed) && modules.none(forbidden::contains) }
            .minWithOrNull(compareBy<Map.Entry<String, List<String>>>({ it.value.size }, { it.key }))
            ?.value
            ?: needed

    private fun split(modules: String) = modules.split(',').map(String::trim).filter(String::isNotEmpty)
}
