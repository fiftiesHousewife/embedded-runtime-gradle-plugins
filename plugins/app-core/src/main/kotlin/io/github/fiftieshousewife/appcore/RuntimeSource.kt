package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.File
import java.net.URI

/**
 * `javaApp.jdk.source` / `nodeApp.node.source`, with `pattern` naming the files: unset, the binary
 * repository's tree or the vendor's site; an `https:` or `file:` URL, a [Mirror] in the vendor's layout; an
 * absolute path, a [Directory] in the curated layout, as is a [Repository] beside the binary repository's;
 * or `yum:` and a URL or repository.
 */
sealed interface RuntimeSource {
    data object Default : RuntimeSource

    data class Mirror(val url: String) : RuntimeSource

    data class Directory(val uri: URI) : RuntimeSource

    data class Repository(val name: String) : RuntimeSource

    data class Yum(val repository: String) : RuntimeSource {
        val isUrl: Boolean get() = URL.containsMatchIn(repository)
    }

    companion object {
        private val URL = Regex("^(https?|file):")
        private val ABSOLUTE_PATH = Regex("""^(/|\\\\|[A-Za-z]:[\\/])""")
        private const val YUM = "yum:"

        fun parse(property: String, value: String?): RuntimeSource {
            val source = value?.trim()?.takeIf(String::isNotEmpty) ?: return Default
            return when {
                source.startsWith(YUM) -> Yum(source.removePrefix(YUM).trimEnd('/'))

                URL.containsMatchIn(source) -> Mirror(source)

                ABSOLUTE_PATH.containsMatchIn(
                    source,
                ) -> Directory(URI.create(File(source).toURI().toString().trimEnd('/') + "/"))

                ':' in source ->
                    throw GradleException(
                        "$property=$source is not a URL, an absolute path, yum:… or a repository name",
                    )

                else -> Repository(source)
            }
        }
    }
}
