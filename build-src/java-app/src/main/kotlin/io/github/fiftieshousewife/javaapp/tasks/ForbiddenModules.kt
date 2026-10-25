package io.github.fiftieshousewife.javaapp.tasks

import java.io.StringReader
import java.util.Properties

/** Which forbidden modules a linked runtime contains, read from the `MODULES` line of its `release` file. */
internal object ForbiddenModules {
    fun present(release: String, forbidden: List<String>): List<String> {
        val modules =
            Properties()
                .apply { load(StringReader(release)) }
                .getProperty("MODULES")
                .orEmpty()
                .trim('"')
                .split(' ')
                .toSet()
        return forbidden.filter { it in modules }
    }

    fun message(present: List<String>) =
        "The runtime contains ${present.joinToString()}, which javaApp.security.forbiddenModules does not allow. " +
            "Remove it from the modules, or from forbiddenModules if it really must ship."
}
