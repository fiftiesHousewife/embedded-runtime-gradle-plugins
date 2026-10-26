package io.github.fiftieshousewife.appcore

import java.net.URI

/**
 * An Ivy-layout repository over a plain HTTP file tree, holding one ecosystem's runtime archives.
 * Runtime archives are resolved as ordinary Gradle dependencies, so they get Gradle's cache,
 * credentials, offline mode and dependency verification (`gradle/verification-metadata.xml`) for
 * free.
 */
data class RuntimeRepository(
    val name: String,
    val baseUrl: URI,
    val artifactPattern: String,
    /** The only Ivy organisation resolved from this repository, and the only one it may serve. */
    val group: String,
) {
    /** The URL Ivy resolves for [artifact]; the JDK toolchain resolver hands this to Gradle directly. */
    fun downloadUri(artifact: RuntimeArtifact): URI {
        val tokens =
            mapOf(
                "organisation" to artifact.group,
                "module" to artifact.module,
                "revision" to artifact.revision,
                "artifact" to artifact.artifact,
                "classifier" to artifact.classifier.orEmpty(),
                "ext" to artifact.extension,
            )
        // As Ivy does: an optional (…) segment appears only when every token inside it has a value.
        val withOptionals = OPTIONAL.replace(artifactPattern) { segment ->
            val inner = segment.groupValues[1]
            if (TOKEN.findAll(inner).all { tokens[it.groupValues[1]].orEmpty().isNotEmpty() }) inner else ""
        }
        val path = TOKEN.replace(withOptionals) { tokens[it.groupValues[1]] ?: it.value }
        return baseUrl.resolve(path.replace("+", "%2B").replace(" ", "%20"))
    }

    companion object {
        /**
         * Default curated (binary repository) layout: one generic repository with files renamed on upload,
         * so every runtime, whatever vendor it came from, sits at a predictable path:
         * ```
         * runtimes/temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz
         * runtimes/nodejs/node/24.22.1/node-24.22.1-linux-x64.tar.gz
         * ```
         */
        const val CURATED_PATTERN = "[organisation]/[module]/[revision]/[artifact]-[revision]-[classifier].[ext]"

        fun slash(url: String): URI = URI.create(url.trimEnd('/') + "/")

        private val OPTIONAL = Regex("""\(([^()]*)\)""")
        private val TOKEN = Regex("""\[([a-z]+)]""")
    }
}

/** One runtime archive, expressed as an Ivy module plus an explicit artifact. */
data class RuntimeArtifact(
    val group: String,
    val module: String,
    val revision: String,
    val artifact: String,
    val classifier: String?,
    val extension: String,
) {
    val notation: String get() = "$group:$module:$revision"

    companion object {
        /** An archive in the curated layout, renamed on upload to the archive's own target id. */
        fun curated(group: String, module: String, revision: String, target: Target) = RuntimeArtifact(
            group,
            module,
            revision,
            artifact = module,
            classifier = target.id,
            extension = target.archiveExtension,
        )
    }
}
