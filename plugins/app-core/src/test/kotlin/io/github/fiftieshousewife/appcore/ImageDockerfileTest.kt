package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageDockerfileTest {
    private val micro = "registry.access.redhat.com/ubi9/ubi-micro:9.8@sha256:abc"
    private val image = ImageModel(
        name = "ubi-micro",
        identity = ImageIdentity("orders", "Apache-2.0", vendor = "com.example", summary = "Orders"),
        java = true,
        base = micro,
        buildImage = "registry.access.redhat.com/ubi9/ubi-minimal:9.8@sha256:def",
        packages = listOf("zlib"),
        environment = emptyMap(),
        labels = emptyMap(),
        ports = emptyList(),
        user = "1001",
        app = true,
    )

    private fun copies(rendered: String) = rendered.lines().filter {
        it.startsWith("COPY --chown") ||
            "--from=files" in it
    }

    @Test
    fun `installs the locked files with rpm, with no package manager and no repository`() {
        val rendered = ImageDockerfile.render(image)
        assertAll(
            { assertTrue("COPY packages/\${TARGETARCH}/ /tmp/packages/" in rendered, rendered) },
            { assertTrue("rpm --checksig /tmp/packages/*.rpm" in rendered, rendered) },
            { assertFalse("dnf" in rendered, rendered) },
            { assertFalse("http" in rendered, rendered) },
        )
    }

    @Test
    fun `copies from what changes least to what changes most, and runs nothing in the final stage`() {
        val rendered = ImageDockerfile.render(image)
        val expected = listOf(
            "COPY --from=files /out/ /",
            "COPY --chown=1001:0 layers/\${TARGETARCH}/runtime/ /",
            "COPY --chown=1001:0 layers/\${TARGETARCH}/libs/ /",
            "COPY --chown=1001:0 layers/\${TARGETARCH}/code/ /",
        )
        assertAll(
            { assertEquals(expected, copies(rendered)) },
            { assertFalse("RUN" in rendered.substringAfter("FROM base"), rendered) },
        )
    }

    @Test
    fun `labels take the build's arguments, and what the project gave is escaped`() {
        val rendered = ImageDockerfile.render(
            image.copy(
                identity = image.identity.copy(summary = "Costs \$5"),
                labels = mapOf("team" to "\$money"),
            ),
        )
        assertAll(
            { assertTrue("version=\"\${APP_VERSION}\"" in rendered, rendered) },
            { assertTrue("summary=\"Costs \\\$5\"" in rendered, rendered) },
            { assertTrue("team=\"\\\$money\"" in rendered, rendered) },
        )
    }

    @Test
    fun `an image of the runtime alone has no app's layers, port or health check`() {
        val rendered = ImageDockerfile.render(image.copy(app = false))
        assertAll(
            { assertFalse("/libs/" in rendered || "/code/" in rendered, rendered) },
            { assertFalse("EXPOSE" in rendered || "HEALTHCHECK" in rendered, rendered) },
            { assertTrue("ENTRYPOINT [\"java\"]" in rendered, rendered) },
        )
    }

    @Test
    fun `a Java app on micro needs zlib, wherever micro is mirrored, and nothing else does`() {
        assertAll(
            { assertEquals(listOf("zlib"), ImageNeeds.packages(java = true, micro)) },
            { assertEquals(listOf("zlib"), ImageNeeds.packages(true, "artifactory.example.com/ubi9/ubi-micro:9.8")) },
            { assertEquals(emptyList<String>(), ImageNeeds.packages(java = false, micro)) },
            { assertEquals(emptyList<String>(), ImageNeeds.packages(true, "ubi9/ubi-minimal:9.8")) },
        )
    }
}
