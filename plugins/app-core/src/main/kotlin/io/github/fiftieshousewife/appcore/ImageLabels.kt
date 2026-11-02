package io.github.fiftieshousewife.appcore

/**
 * The OCI labels, and those Red Hat's certification requires, which would otherwise be UBI's own and name Red
 * Hat, which it refuses. `vendor` defaults to the project's group; `label(…)` replaces any of them. Values are as
 * the Dockerfile writes them: what the project gave escaped, the build's arguments left for the build to fill in.
 */
internal object ImageLabels {
    private const val OCI = "org.opencontainers.image"

    fun of(image: ImageModel): Map<String, String> {
        val identity = image.identity
        val name = DockerfileTemplate.escape(identity.name)
        val title = if (image.app) name else "$name-runtime"
        val summary = DockerfileTemplate.escape(identity.summary).ifEmpty { title }
        val source = identity.source?.let(DockerfileTemplate::escape)
        val licenses = DockerfileTemplate.escape(identity.licenses)
        return oci(title, summary, licenses, source) + redHat(title, summary, identity) +
            source?.let { mapOf("url" to it) }.orEmpty()
    }

    private fun oci(title: String, summary: String, licenses: String, source: String?) = linkedMapOf(
        "$OCI.title" to title,
        "$OCI.description" to summary,
        "$OCI.licenses" to licenses,
        "$OCI.version" to "\${APP_VERSION}",
        "$OCI.revision" to "\${GIT_COMMIT}",
        "$OCI.created" to "\${CREATED}",
        "$OCI.base.name" to "\${BASE_IMAGE}",
    ).also { labels -> source?.let { labels["$OCI.source"] = it } }

    private fun redHat(title: String, summary: String, identity: ImageIdentity): Map<String, String> {
        val vendor = DockerfileTemplate.escape(identity.vendor)
        return mapOf(
            "name" to title,
            "vendor" to vendor,
            "maintainer" to vendor,
            "version" to "\${APP_VERSION}",
            "release" to "\${RELEASE}",
            "summary" to summary,
            "description" to summary,
            "vcs-ref" to "\${GIT_COMMIT}",
            "build-date" to "\${CREATED}",
            "io.k8s.display-name" to title,
            "io.k8s.description" to summary,
        )
    }
}
