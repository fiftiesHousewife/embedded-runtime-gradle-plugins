package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class DockerfileTemplateTest {
    private val template = DockerfileTemplateTest::class.java.getResource(
        "/io/github/fiftieshousewife/appcore/docker/Dockerfile",
    )!!.readText()
    private val identity =
        ImageIdentity(
            name = "orders",
            version = "1.4.2",
            revision = "902c348",
            licenses = "Apache-2.0",
            created = "2026-10-26T09:15:00+00:00",
            source = "https://github.com/example/orders",
        )
    private val rendered = DockerfileTemplate.render(template, identity)

    @Test
    fun `labels the image with the OCI annotations for the app, its commit and its base image`() {
        val labels =
            mapOf(
                "title" to "orders",
                "version" to "1.4.2",
                "revision" to "902c348",
                "created" to "2026-10-26T09:15:00+00:00",
                "source" to "https://github.com/example/orders",
                "licenses" to "Apache-2.0",
                "base.name" to "\${BASE_IMAGE}",
                "base.digest" to "\${BASE_DIGEST}",
            )
        assertAll(
            labels.map { (key, value) ->
                Executable { assertTrue(rendered.contains("org.opencontainers.image.$key=\"$value\""), rendered) }
            },
        )
    }

    @Test
    fun `leaves out the labels it has no value for`() {
        val bare = DockerfileTemplate.render(template, identity.copy(created = null, source = null))
        assertAll(
            { assertFalse(bare.contains("image.created"), bare) },
            { assertFalse(bare.contains("image.source"), bare) },
            { assertTrue(bare.contains("""org.opencontainers.image.licenses="Apache-2.0" \"""), bare) },
        )
    }

    @Test
    fun `escapes quotes, backslashes and dollars so a label holds exactly its value`() {
        val odd = DockerfileTemplate.render(template, identity.copy(version = "1.0 \"beta\" \$HOME \\"))
        assertTrue(odd.contains("""org.opencontainers.image.version="1.0 \"beta\" \${'$'}HOME \\""""), odd)
    }

    @Test
    fun `pins the base image by digest and copies the archive for the CPU being built`() {
        assertAll(
            { assertTrue(rendered.contains("ARG BASE_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:"), rendered) },
            { assertTrue(Regex("ARG BASE_DIGEST=sha256:[0-9a-f]{64}\n").containsMatchIn(rendered), rendered) },
            { assertTrue(rendered.contains("FROM \${BASE_IMAGE}@\${BASE_DIGEST}"), rendered) },
            { assertTrue(rendered.contains("COPY --chown=1001:0 app/\${TARGETARCH}/ /app/"), rendered) },
        )
    }

    @Test
    fun `starts and health-checks the app through its own launcher and leaves no placeholder`() {
        assertAll(
            { assertTrue(rendered.contains("""ENTRYPOINT ["/app/bin/orders", "run"]"""), rendered) },
            { assertTrue(rendered.contains("""CMD ["/app/bin/orders", "healthcheck", "ready"]"""), rendered) },
            { assertFalse(Regex("@[A-Z_]+@").containsMatchIn(rendered), rendered) },
        )
    }
}
