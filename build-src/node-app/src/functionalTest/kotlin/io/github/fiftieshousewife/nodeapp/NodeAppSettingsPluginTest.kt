package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NodeAppSettingsPluginTest {
    @TempDir
    lateinit var tmp: File

    private fun project(properties: String): TestProject =
        TestProject(tmp).apply {
            file("gradle.properties", properties)
            file(
                "settings.gradle.kts",
                """
            plugins { id("${NodeAppSettingsPlugin.ID}") }
            rootProject.name = "probe"
            dependencyResolutionManagement.repositories.forEach { repo ->
                val url = (repo as? org.gradle.api.artifacts.repositories.UrlArtifactRepository)?.url
                println("REPO ${'$'}{repo.name} ${'$'}url")
            }
            """,
            )
        }

    @Test
    fun `without artifactory Node comes from nodejs-org`() {
        val output = project("").build("help").output

        assertTrue(output.contains("REPO MavenRepo https://repo.maven.apache.org/maven2/"), output)
        assertTrue(output.contains("REPO nodejsDist https://nodejs.org/dist/"), output)
        assertTrue(!output.contains("Jdks"), "the Node settings plugin must not add JDK repositories:\n$output")
    }

    @Test
    fun `artifactory serves Node from the curated runtimes repository`() {
        val output = project("artifactory.url=https://artifactory.example.com/artifactory").build("help").output

        assertTrue(output.contains("REPO artifactoryNode https://artifactory.example.com/artifactory/runtimes/"), output)
        assertTrue(!output.contains("nodejs.org"), output)
    }
}
