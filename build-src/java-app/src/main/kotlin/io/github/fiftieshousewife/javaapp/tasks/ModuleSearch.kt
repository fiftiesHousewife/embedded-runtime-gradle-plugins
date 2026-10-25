package io.github.fiftieshousewife.javaapp.tasks

/** What [JlinkFindModules] reads from a JDK and from a failing app, and what it reports. */
internal object ModuleSearch {
    private const val MODULE = "Module: "
    private val MISSING_CLASS = Regex("""(?:ClassNotFoundException|NoClassDefFoundError)[:\s]+([\w$./]+)""")

    /** Failures that name no class but always mean one module. */
    private val SYMPTOMS =
        mapOf(
            "UnsupportedCharsetException" to "jdk.charsets",
            "sun.util.resources.provider" to "jdk.localedata",
        )

    /** Which module holds each package, from the output of `jimage list` on a JDK's `lib/modules`. */
    fun packagesToModules(jimageListing: String): Map<String, String> = jimageListing
        .split(MODULE)
        .drop(1)
        .flatMap { section ->
            val lines = section.lines().map(String::trim)
            lines.drop(1).filter { it.endsWith(".class") }.map { packageOf(it) to lines.first() }
        }.toMap()

    /** The JDK modules [output] shows to be missing, given which module holds each package. */
    fun missingModules(output: String, packages: Map<String, String>): Set<String> {
        val fromClasses =
            MISSING_CLASS
                .findAll(output)
                .map { it.groupValues[1].replace('/', '.') }
                .mapNotNull { packages[it.substringBeforeLast('.', "")] }
        val fromSymptoms = SYMPTOMS.filterKeys { it in output }.values
        return (fromClasses + fromSymptoms).toSet()
    }

    /** What to tell the user once the app is healthy: the modules to add, or that none are needed. */
    fun report(appName: String, added: Collection<String>) = if (added.isEmpty()) {
        "$appName is healthy with its current modules; nothing to add."
    } else {
        "$appName is healthy once these modules are added:\n\n" +
            "  javaApp { additionalModules.addAll(${added.joinToString { "\"$it\"" }}) }"
    }

    private fun packageOf(classPath: String) = classPath.substringBeforeLast('/', "").replace('/', '.')
}
