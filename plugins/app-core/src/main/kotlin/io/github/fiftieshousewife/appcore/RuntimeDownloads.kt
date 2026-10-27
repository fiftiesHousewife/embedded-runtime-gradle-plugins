package io.github.fiftieshousewife.appcore

import java.net.URI

/** As ordinary Gradle dependencies, runtime archives get Gradle's cache, credentials, offline mode, verification. */
data class RuntimeRepository(
    val name: String,
    val baseUrl: URI,
    /** The pattern Ivy resolves: a curated pattern's OS and CPU tokens are folded into `[classifier]`. */
    val artifactPattern: String,
    val group: String,
    /** The stretch of a curated pattern holding [TargetTokens], each target's classifier once rendered. */
    private val classifierTemplate: String? = null,
) {
    /** Renamed on upload to the target id by default, or as the pattern says with [TargetTokens] and [tokens]. */
    fun curated(
        module: String,
        revision: String,
        target: Target,
        tokens: Map<String, String> = emptyMap(),
    ): RuntimeArtifact {
        val ivy = mapOf(
            "organisation" to group,
            "module" to module,
            "artifact" to module,
            "revision" to revision,
            "ext" to target.archiveExtension,
        )
        return RuntimeArtifact(
            group,
            module,
            revision,
            artifact = module,
            classifier = classifierTemplate?.let { TargetTokens.render(it, target, ivy + tokens) } ?: target.id,
            extension = target.archiveExtension,
        )
    }

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
         * Files renamed on upload, so every runtime, whatever its vendor, sits at a predictable path such as
         * `runtimes/temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz`.
         */
        const val CURATED_PATTERN = "[organisation]/[module]/[revision]/[artifact]-[revision]-[classifier].[ext]"

        fun slash(url: String): URI = URI.create(url.trimEnd('/') + "/")

        fun curated(name: String, baseUrl: URI, pattern: String, group: String): RuntimeRepository {
            val (ivyPattern, classifierTemplate) = TargetTokens.split(pattern)
            return RuntimeRepository(name, baseUrl, ivyPattern, group, classifierTemplate)
        }

        private val OPTIONAL = Regex("""\(([^()]*)\)""")
        private val TOKEN = Regex("""\[([a-z]+)]""")
    }
}

data class RuntimeArtifact(
    val group: String,
    val module: String,
    val revision: String,
    val artifact: String,
    val classifier: String?,
    val extension: String,
) {
    val notation: String get() = "$group:$module:$revision"
}
