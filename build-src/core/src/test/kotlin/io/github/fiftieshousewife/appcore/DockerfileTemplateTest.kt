package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerfileTemplateTest {
    private val template = DockerfileTemplateTest::class.java.getResource(
        "/io/github/fiftieshousewife/appcore/docker/Dockerfile",
    )!!.readText()
    private val rendered = DockerfileTemplate.render(template, ImageIdentity("orders", "1.4.2", "902c348"))

    @Test
    fun `labels the image with the OCI title, version and revision annotations`() {
        assertAll(
            { assertTrue(rendered.contains("""org.opencontainers.image.title="orders""""), rendered) },
            { assertTrue(rendered.contains("""org.opencontainers.image.version="1.4.2""""), rendered) },
            { assertTrue(rendered.contains("""org.opencontainers.image.revision="902c348""""), rendered) },
        )
    }

    @Test
    fun `starts and health-checks the app through its own launcher and leaves no placeholder`() {
        assertAll(
            { assertTrue(rendered.contains("""ENTRYPOINT ["/app/bin/orders", "run"]"""), rendered) },
            { assertTrue(rendered.contains("""CMD ["/app/bin/orders", "healthcheck"]"""), rendered) },
            { assertFalse(rendered.contains("@"), rendered) },
        )
    }
}
