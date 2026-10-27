package io.github.fiftieshousewife.appcore

import java.net.URI

/** A git remote turned into a URL that is safe to publish in an image label. */
internal object SourceUrl {
    private val scpLike = Regex("""^[\w.-]+@([\w.-]+):(?!//)(.+)$""")

    /**
     * `git@github.com:org/app.git` and `ssh://git@host/org/app` become `https://<host>/org/app.git`
     * and `https://host/org/app`; an https remote loses any user name or token written into it. A
     * local path, or anything else that is not a URL, gives null and the label is left out.
     */
    fun fromRemote(remote: String): String? =
        scpLike.matchEntire(remote)?.destructured?.let { (host, path) -> "https://$host/${path.trimStart('/')}" }
            ?: runCatching { URI(remote) }.getOrNull()?.takeIf { it.host != null }?.let(::withoutCredentials)

    private fun withoutCredentials(uri: URI): String? = when (uri.scheme) {
        "https", "http" -> URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        "ssh", "git" -> URI("https", null, uri.host, -1, uri.path, null, null).toString()
        else -> null
    }
}
