package io.github.fiftieshousewife.javaapp

import java.util.Properties

/**
 * Shared runtime module sets. Linking exactly the modules each app needs gives every app its own
 * runtime layer; linking the smallest of a few fixed sets instead lets apps on the same set, JDK and
 * target ship byte-identical runtimes, which container registries and nodes store and pull once.
 * The default sets are data, in `module-sets.properties`, so they can be tuned without code.
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

    /** The default sets for Java [feature], by name. */
    fun defaults(feature: Int): Map<String, List<String>> = parse(catalogue, feature)

    /**
     * `set.<name>` entries, without the modules a `lastFeature.<module>` entry retires before
     * [feature]: a set naming a module the JDK no longer has would fail to link.
     */
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

    /**
     * The smallest of [sets] holding all of [needed] and none of [forbidden]: fewest modules, then
     * name, so the choice never depends on the order sets are declared in. [needed] itself when no
     * set qualifies, so an app with an unusual need still links, just without sharing.
     */
    fun choose(needed: List<String>, sets: Map<String, List<String>>, forbidden: List<String>): List<String> =
        sets.entries
            .filter { (_, modules) -> modules.containsAll(needed) && modules.none(forbidden::contains) }
            .minWithOrNull(compareBy<Map.Entry<String, List<String>>>({ it.value.size }, { it.key }))
            ?.value
            ?: needed

    private fun split(modules: String) = modules.split(',').map(String::trim).filter(String::isNotEmpty)
}
