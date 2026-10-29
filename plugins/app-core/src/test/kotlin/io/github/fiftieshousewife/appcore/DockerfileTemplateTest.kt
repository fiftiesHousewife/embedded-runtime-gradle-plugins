package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class DockerfileTemplateTest {
    private val template = DockerfileTemplateTest::class.java.getResource(
        "/io/github/fiftieshousewife/appcore/docker/Dockerfile",
    )!!.readText()
    private val identity = ImageIdentity("orders", "Apache-2.0", "https://github.com/example/orders")
    private val rendered = DockerfileTemplate.render(template, identity)

    @Test
    fun `labels the image for the app, and takes this build's release, commit and time as ARGs`() {
        val labels =
            mapOf(
                "title" to "orders",
                "source" to "https://github.com/example/orders",
                "licenses" to "Apache-2.0",
                "version" to "\${APP_VERSION}",
                "revision" to "\${GIT_COMMIT}",
                "created" to "\${CREATED}",
                "base.name" to "\${BASE_IMAGE}",
            )
        assertAll(
            labels.map { (key, value) ->
                Executable { assertTrue(rendered.contains("org.opencontainers.image.$key=\"$value\""), rendered) }
            } + listOf("APP_VERSION", "GIT_COMMIT", "CREATED").map { argument ->
                Executable { assertTrue(rendered.contains("\nARG $argument\n"), argument) }
            },
        )
    }

    @Test
    fun `nothing in it changes from one commit to the next, so the project's file only changes with the plugin`() {
        val again = DockerfileTemplate.render(template, identity)
        assertAll(
            { assertEquals(rendered, again) },
            { assertFalse(Regex("@[A-Z_]+@").containsMatchIn(rendered), rendered) },
        )
    }

    @Test
    fun `leaves out the source it has no value for, and escapes quotes, backslashes and dollars`() {
        val odd = DockerfileTemplate.render(template, ImageIdentity("orders", "LicenseRef-\"odd\" \$X \\"))
        assertAll(
            { assertFalse(odd.contains("image.source"), odd) },
            { assertTrue(odd.contains("""org.opencontainers.image.licenses="LicenseRef-\"odd\" \${'$'}X \\""""), odd) },
        )
    }

    @Test
    fun `pins the base image by digest and copies the archive for the CPU being built`() {
        val pinned =
            Regex(
                """ARG BASE_IMAGE=registry\.access\.redhat\.com/ubi9/ubi-minimal:\d+\.\d+-\d+@sha256:[0-9a-f]{64}\n""",
            )
        assertAll(
            { assertTrue(pinned.containsMatchIn(rendered), rendered) },
            { assertTrue(rendered.contains("FROM \${BASE_IMAGE}\n"), rendered) },
            { assertTrue(rendered.contains("COPY --chown=1001:0 layers/\${TARGETARCH}/runtime/ /"), rendered) },
            { assertTrue(rendered.contains("COPY --chown=1001:0 layers/\${TARGETARCH}/app/ /"), rendered) },
            { assertTrue(rendered.contains("""ENTRYPOINT ["/app/bin/orders", "run"]"""), rendered) },
            { assertTrue(rendered.contains("""CMD ["/app/bin/orders", "healthcheck", "ready"]"""), rendered) },
        )
    }

    @Test
    fun `the context's copy gets this build's values, and only for bare ARGs`() {
        val copy = ContextDockerfile.render(
            "ARG APP_VERSION\nARG GIT_COMMIT\nARG CREATED\nARG OTHER\nARG KEPT=as-is\n",
            mapOf("APP_VERSION" to "1.4.2", "GIT_COMMIT" to "902c348", "KEPT" to "no"),
        )
        assertEquals(
            "ARG APP_VERSION=\"1.4.2\"\nARG GIT_COMMIT=\"902c348\"\nARG CREATED\nARG OTHER\nARG KEPT=as-is\n",
            copy,
        )
    }
}

class ManagedDockerfileTest {
    private val block = "FROM scratch\nENTRYPOINT [\"/app/bin/orders\", \"run\"]\n"

    @Test
    fun `a missing Dockerfile is created holding just the plugins' block`() {
        val created = ManagedDockerfile.update(null, block)!!
        assertAll(
            { assertTrue(created.startsWith("# >>> embedded-runtime"), created) },
            { assertTrue(created.contains("FROM scratch\n"), created) },
            { assertTrue(created.endsWith("# <<< embedded-runtime\n"), created) },
        )
    }

    @Test
    fun `only the block is rewritten, and what the project wrote around it stays`() {
        val ours = "# syntax=docker/dockerfile:1\n" + ManagedDockerfile.update(null, "FROM old\n") +
            "USER root\nRUN microdnf install -y fontconfig\nUSER 1001\n"
        val updated = ManagedDockerfile.update(ours, block)!!
        assertAll(
            { assertTrue(updated.startsWith("# syntax=docker/dockerfile:1\n# >>> embedded-runtime"), updated) },
            { assertFalse(updated.contains("FROM old"), updated) },
            { assertTrue(updated.contains("FROM scratch"), updated) },
            {
                assertTrue(
                    updated.endsWith(
                        "# <<< embedded-runtime\nUSER root\nRUN microdnf install -y fontconfig\nUSER 1001\n",
                    ),
                    updated,
                )
            },
        )
    }

    @Test
    fun `an up to date Dockerfile is left byte for byte as it is`() {
        val current = ManagedDockerfile.update(null, block)!! + "RUN echo mine\n"
        assertEquals(current, ManagedDockerfile.update(current, block))
    }

    @Test
    fun `a Dockerfile without the block is the project's own, and untouched`() {
        assertNull(ManagedDockerfile.update("FROM eclipse-temurin:21\nCOPY app/ /app/\n", block))
    }
}
