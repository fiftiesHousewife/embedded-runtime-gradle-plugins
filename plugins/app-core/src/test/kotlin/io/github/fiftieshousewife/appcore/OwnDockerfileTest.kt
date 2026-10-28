package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OwnDockerfileTest {
    @TempDir
    lateinit var dir: File

    private fun dockerfile(copy: String) = dir.resolve("Dockerfile").apply { writeText("FROM scratch\n$copy\n") }

    @Test
    fun `a Dockerfile that copies the runtime and the app for the CPU being built is used as it is`() {
        val file = dockerfile(
            "ARG TARGETARCH\nCOPY runtime/${'$'}{TARGETARCH}/ /app/runtime/\n" +
                "COPY --chown=1001:0 app/${'$'}{TARGETARCH}/ /app/",
        )
        assertEquals(file.readText(), OwnDockerfile.read(file))
    }

    @Test
    fun `a Dockerfile that copies all of app, as before every CPU had its own, is refused with the fix`() {
        listOf("COPY app/ /app/", "COPY --chown=1001:0 app /app", "ADD app/ /opt/app/").forEach { copy ->
            val failure = assertThrows(GradleException::class.java) { OwnDockerfile.read(dockerfile(copy)) }
            assertTrue(failure.message!!.contains("COPY app/${'$'}{TARGETARCH}/ /app/"), copy)
        }
    }

    @Test
    fun `a Dockerfile that copies the app without its runtime is refused with the fix`() {
        val copy = "ARG TARGETARCH\nCOPY app/${'$'}{TARGETARCH}/ /app/"
        val failure = assertThrows(GradleException::class.java) { OwnDockerfile.read(dockerfile(copy)) }
        assertTrue(failure.message!!.contains("COPY runtime/${'$'}{TARGETARCH}/ /app/runtime/"), failure.message)
    }

    @Test
    fun `variants are the Dockerfiles beside the default`() {
        listOf("Dockerfile.openshift", "Dockerfile.micro", "Dockerfile.dockerignore", "README.md")
            .forEach { dir.resolve(it).writeText("") }
        val variants = ImageVariant.find(dir)
        assertAll(
            {
                assertEquals(
                    listOf("Dockerfile", "Dockerfile.micro", "Dockerfile.openshift"),
                    variants.map { it.dockerfile },
                )
            },
            { assertEquals(listOf("", "Micro", "Openshift"), variants.map { it.taskSuffix }) },
        )
    }
}
