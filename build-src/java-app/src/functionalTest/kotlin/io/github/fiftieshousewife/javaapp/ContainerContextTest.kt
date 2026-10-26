package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** `appDockerContext` and `appDockerfile`: what a container image is built from. */
class ContainerContextTest {
    @TempDir
    lateinit var tmp: File

    private val linuxForThisCpu = "linux-" + TestProject.hostTarget().substringAfter('-')
    private val host = TestProject.hostTarget()

    @Test
    fun `the container context holds the project's own Dockerfile and every Linux archive, by CPU`() {
        val project =
            javaService(
                tmp,
                JavaServiceOptions(javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-x64\", \"linux-aarch64\")"),
            )
        JdkFixture.publish(project, "linux-x64", "linux-aarch64")
        project.file("Dockerfile", "# the project's own\nFROM scratch")
        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context")
        assertAll(
            { assertEquals("# the project's own\nFROM scratch\n", context.resolve("Dockerfile").readText()) },
            { assertTrue(context.resolve("app/amd64/bin/svc").isFile) },
            { assertTrue(context.resolve("app/arm64/bin/svc").isFile) },
            {
                assertTrue(
                    context.resolve("app/arm64/META-INF/app.json").readText().contains(""""target": "linux-aarch64""""),
                )
            },
        )
    }

    @Test
    fun `the generated Dockerfile is labelled with the app's licence and source`() {
        val source = "https://git.example.com/team/svc"
        val project =
            javaService(
                tmp,
                JavaServiceOptions(
                    javaApp = "$FIXTURE_MODULES\ntargets(\"$linuxForThisCpu\")\nlicense = \"Apache-2.0\"\n" +
                        "sourceUrl = \"$source\"",
                ),
            )
        JdkFixture.publish(project, linuxForThisCpu)
        project.build("appDockerfile")
        val dockerfile = project.project.resolve("build/app-work/docker/Dockerfile").readText()
        assertAll(
            { assertTrue(dockerfile.contains("""org.opencontainers.image.licenses="Apache-2.0""""), dockerfile) },
            { assertTrue(dockerfile.contains("""org.opencontainers.image.source="$source""""), dockerfile) },
            { assertTrue(dockerfile.contains("ARG BASE_DIGEST=sha256:"), dockerfile) },
        )
    }
}
