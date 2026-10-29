package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.time.Instant

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
        project.file("Dockerfile.openshift", "FROM scratch\nARG APP_VERSION")
        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context")
        assertAll(
            { assertEquals("# the project's own\nFROM scratch\n", context.resolve("Dockerfile").readText()) },
            { assertTrue(context.resolve("Dockerfile.openshift").readText().contains("ARG APP_VERSION=\"2.0.0\"")) },
            { assertTrue(context.resolve("app/amd64/bin/svc").isFile) },
            { assertTrue(context.resolve("runtime/arm64/release").isFile) },
            { assertFalse(context.resolve("app/arm64/runtime").exists()) },
            {
                assertTrue(
                    context.resolve("app/arm64/META-INF/app.json").readText().contains(""""target": "linux-aarch64""""),
                )
            },
            { assertTrue(context.resolve("layers/amd64/runtime/app/runtime/release").isFile) },
            { assertTrue(context.resolve("layers/arm64/app/app/bin/svc").isFile) },
            { assertFalse(context.resolve("layers/arm64/app/app/runtime").exists()) },
        )
    }

    @Test
    fun `every file and directory in the context has one fixed time, so its image layers repeat`() {
        val project =
            javaService(tmp, JavaServiceOptions(javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-x64\")"))
        JdkFixture.publish(project, "linux-x64")
        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context").toPath()
        val times = Files.walk(context).use { paths ->
            paths.map { Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS) }.distinct().toList()
        }
        assertEquals(listOf(FileTime.from(Instant.parse("1980-02-01T00:00:00Z"))), times)
    }

    @Test
    fun `the project's Dockerfile is created at its root, labelled, and the context's copy carries this build`() {
        val source = "https://git.example.com/team/svc"
        val project =
            javaService(
                tmp,
                JavaServiceOptions(
                    javaApp = "$FIXTURE_MODULES\ntargets(\"$linuxForThisCpu\")\nlicense = \"Apache-2.0\"",
                ),
            )
        withOrigin(project, "https://ci:token@git.example.com/team/svc")
        JdkFixture.publish(project, linuxForThisCpu)
        project.build("appDockerContextFile")
        val dockerfile = project.project.resolve("Dockerfile").readText()
        val copy = project.project.resolve("build/app-work/docker/Dockerfile").readText()
        assertAll(
            { assertTrue(dockerfile.startsWith("# >>> embedded-runtime"), dockerfile) },
            { assertTrue(dockerfile.contains("""org.opencontainers.image.licenses="Apache-2.0""""), dockerfile) },
            { assertTrue(dockerfile.contains("""org.opencontainers.image.source="$source""""), dockerfile) },
            { assertTrue(Regex("ARG BASE_IMAGE=\\S+@sha256:").containsMatchIn(dockerfile), dockerfile) },
            { assertTrue(dockerfile.contains("\nARG APP_VERSION\n"), dockerfile) },
            { assertTrue(copy.contains("\nARG APP_VERSION=\"2.0.0\"\n"), copy) },
        )
    }

    @Test
    fun `the plugins' block is updated, and what the project added after it stays`() {
        val project = javaService(tmp, JavaServiceOptions(javaApp = "$FIXTURE_MODULES\ntargets(\"$linuxForThisCpu\")"))
        JdkFixture.publish(project, linuxForThisCpu)
        project.build("appDockerfile")
        val dockerfile = project.project.resolve("Dockerfile")
        // The project's own line, and a stale block from an older release.
        dockerfile.writeText(dockerfile.readText().replace("EXPOSE 8080", "EXPOSE 9999") + "LABEL team=\"orders\"\n")
        project.build("appDockerfile")
        val updated = dockerfile.readText()
        assertAll(
            { assertTrue(updated.contains("EXPOSE 8080") && !updated.contains("EXPOSE 9999"), updated) },
            { assertTrue(updated.endsWith("# <<< embedded-runtime\nLABEL team=\"orders\"\n"), updated) },
        )
    }

    private fun withOrigin(project: TestProject, remote: String) {
        TestProject.run("git", "-C", project.project.path, "init", "-q")
        TestProject.run("git", "-C", project.project.path, "remote", "add", "origin", remote)
    }
}
