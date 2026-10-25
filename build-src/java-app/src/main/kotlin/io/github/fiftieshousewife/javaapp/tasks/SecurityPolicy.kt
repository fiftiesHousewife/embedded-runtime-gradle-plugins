package io.github.fiftieshousewife.javaapp.tasks

import java.util.Properties

/**
 * The organisation's changes to `conf/security/java.security`, applied in order: properties files,
 * then single properties, then additions to comma-separated lists, then the lock.
 */
internal data class SecurityPolicy(
    val files: List<Properties>,
    val properties: Map<String, String>,
    /** Security property → values to add to its comma-separated list, keeping what is already there. */
    val additions: Map<String, List<String>>,
    val lock: Boolean,
) {
    /** Every property this policy sets, with its final value, given the runtime's [original] file. */
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

        /** [current]'s comma-separated values followed by any of [extra] not already among them. */
        fun withAdditions(current: String?, extra: List<String>): String {
            val values = current.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            return (values + extra.filter { it !in values }).joinToString(", ")
        }

        /**
         * The lines appended to `java.security`. Later entries win when the file is read, so the JDK's
         * own file, comments and all, stays intact above them.
         */
        fun block(changes: Map<String, String>, record: String) =
            "\n# --- Set by io.github.fiftieshousewife.java-app (see $record) ---\n" +
                changes.entries.joinToString("") { (k, v) -> "$k=${escape(v)}\n" }

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
    }
}
