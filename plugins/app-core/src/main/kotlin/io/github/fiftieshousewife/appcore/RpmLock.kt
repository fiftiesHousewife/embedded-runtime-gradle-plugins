package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException

/** One locked RPM: exactly which file, from where, and its SHA-256. */
data class LockedRpm(
    val name: String,
    val evr: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val repoid: String,
    val sourceRpm: String,
) {
    val fileName: String get() = url.substringAfterLast('/')

    /**
     * Where to fetch it here: the repository this build configures under [repoid], with the package's path in
     * it, so a lock written against Red Hat's repositories serves a build that reaches only a mirror of them.
     * The SHA-256 makes it the same file wherever it comes from.
     */
    fun urlIn(roots: Map<String, String>): String {
        val root = roots[repoid] ?: return url
        val path = url.indexOf(PACKAGES).takeIf { it >= 0 }?.let { url.substring(it + 1) } ?: return url
        return root.trimEnd('/') + "/" + path
    }

    private companion object {
        const val PACKAGES = "/Packages/"
    }
}

/**
 * The RPMs an image adds to its base, pinned per CPU, in the layout of Konflux's `rpms.lock.yaml`
 * (`lockfileVersion: 1`), so Red Hat's own tooling and Renovate can read it. Written and read here
 * without a YAML library: the plugins write only this shape, and read only the keys they use.
 */
data class RpmLock(val arches: Map<String, List<LockedRpm>>) {
    fun packages(arch: String): List<LockedRpm> = arches[arch].orEmpty()

    /** What [wanted] needs that this lock doesn't pin for [arch], so the build can say to lock again. */
    fun missing(wanted: Collection<String>, arch: String): List<String> =
        wanted.filter { name -> packages(arch).none { it.name == name } }

    fun render(): String = buildString {
        appendLine("---")
        appendLine("lockfileVersion: 1")
        appendLine("lockfileVendor: redhat")
        appendLine("arches:")
        arches.toSortedMap().forEach { (arch, rpms) ->
            appendLine("- arch: $arch")
            appendLine("  packages:")
            rpms.sortedBy { it.name }.forEach { rpm ->
                appendLine("  - url: ${rpm.url}")
                appendLine("    repoid: ${rpm.repoid}")
                appendLine("    size: ${rpm.size}")
                appendLine("    checksum: sha256:${rpm.sha256}")
                appendLine("    name: ${rpm.name}")
                appendLine("    evr: ${rpm.evr}")
                appendLine("    sourcerpm: ${rpm.sourceRpm}")
            }
            appendLine("  source: []")
            appendLine("  module_metadata: []")
        }
    }

    companion object {
        fun parse(text: String): RpmLock {
            val arches = linkedMapOf<String, MutableList<Map<String, String>>>()
            var arch: String? = null
            text.lines().map(String::trimEnd).filter { it.isNotBlank() }.forEach { line ->
                val trimmed = line.trim().removePrefix("- ")
                val key = trimmed.substringBefore(':')
                val value = trimmed.substringAfter(':', "").trim()
                when {
                    line.startsWith("- arch:") -> arch = value.also { arches[it] = mutableListOf() }

                    line.trimStart().startsWith("- url:") ->
                        arch?.let { arches.getValue(it).add(mutableMapOf(key to value)) }

                    key in FIELDS && line.startsWith("    ") ->
                        arch?.let { (arches.getValue(it).last() as MutableMap)[key] = value }
                }
            }
            return RpmLock(arches.mapValues { (_, rpms) -> rpms.map(::locked) })
        }

        private fun locked(fields: Map<String, String>) = LockedRpm(
            name = fields["name"] ?: throw GradleException("A locked package has no name: $fields"),
            evr = fields["evr"].orEmpty(),
            url = fields.getValue("url"),
            sha256 = fields["checksum"]?.removePrefix("sha256:")?.lowercase()
                ?: throw GradleException("${fields["name"]} has no checksum in the lock"),
            size = fields["size"]?.toLongOrNull() ?: 0,
            repoid = fields["repoid"].orEmpty(),
            sourceRpm = fields["sourcerpm"].orEmpty(),
        )

        private val FIELDS = setOf("repoid", "size", "checksum", "name", "evr", "sourcerpm")
    }
}
