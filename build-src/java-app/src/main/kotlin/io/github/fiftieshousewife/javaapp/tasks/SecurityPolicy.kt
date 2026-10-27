package io.github.fiftieshousewife.javaapp.tasks

import java.util.Properties

/** Applied in order: properties files, single properties, additions to comma-separated lists, then the lock. */
internal data class SecurityPolicy(
    val files: List<Properties>,
    val properties: Map<String, String>,
    /** Security property → values to add to its comma-separated list, keeping what is already there. */
    val additions: Map<String, List<String>>,
    val lock: Boolean,
) {
    fun changes(original: Properties): Map<String, String> {
        val changes = LinkedHashMap<String, String>()
        files.flatMap { it.entries }.forEach { (k, v) -> changes[k.toString()] = v.toString() }
        changes.putAll(properties)
        additions
            .filterValues { it.isNotEmpty() }
            .forEach { (key, extra) -> changes[key] = withAdditions(changes[key] ?: original.getProperty(key), extra) }
        if (lock) changes[OVERRIDE] = "false"
        return changes
    }

    companion object {
        /** `security.overridePropertiesFile=false` stops `-Djava.security.properties` loosening the policy. */
        const val OVERRIDE = "security.overridePropertiesFile"

        fun withAdditions(current: String?, extra: List<String>): String {
            val values = current.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            return (values + extra.filter { it !in values }).joinToString(", ")
        }

        /** Appended: later entries win when the file is read, so the JDK's own file stays intact above them. */
        fun block(changes: Map<String, String>, record: String) =
            "\n# --- Set by io.github.fiftieshousewife.java-app (see $record) ---\n" +
                changes.entries.joinToString("") { (k, v) -> "$k=${escape(v)}\n" }

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
    }
}
