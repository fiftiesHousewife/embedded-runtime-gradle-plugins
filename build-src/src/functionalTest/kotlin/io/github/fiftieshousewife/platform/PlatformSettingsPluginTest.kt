package io.github.fiftieshousewife.platform

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PlatformSettingsPluginTest {
    @TempDir
    lateinit var tmp: File

    private fun project(properties: String): TestProject =
        TestProject(tmp).apply {
            file("gradle.properties", properties)
            file(
                "settings.gradle.kts",
                """
            plugins { id("io.github.fiftieshousewife.platform.settings") }
            rootProject.name = "probe"
            dependencyResolutionManagement.repositories.forEach { repo ->
                val url = (repo as? org.gradle.api.artifacts.repositories.UrlArtifactRepository)?.url
                println("REPO ${'$'}{repo.name} ${'$'}url")
            }
            pluginManagement.repositories.forEach { println("PLUGINS ${'$'}{it.name}") }
            """,
            )
        }

    @Test
    fun `public mode uses Maven Central, the plugin portal and upstream runtime downloads`() {
        val output = project("platform.repositories=public").build("help").output

        assertTrue(output.contains("REPO MavenRepo https://repo.maven.apache.org/maven2/"), output)
        assertTrue(output.contains("REPO temurinJdks https://github.com/adoptium/"), output)
        assertTrue(output.contains("REPO nodejsDist https://nodejs.org/dist/"), output)
        assertTrue(output.contains("PLUGINS Gradle Central Plugin Repository"), output)
    }

    @Test
    fun `artifactory mode routes everything through the configured instance`() {
        val output =
            project(
                """
            platform.repositories=artifactory
            platform.artifactory.url=https://artifactory.example.com/artifactory
            platform.artifactory.repo.maven=maven-remote
            """,
            ).build("help").output

        assertTrue(output.contains("REPO artifactoryRuntimes https://artifactory.example.com/artifactory/runtimes/"), output)
        assertTrue(output.contains("REPO artifactory https://artifactory.example.com/artifactory/maven-remote/"), output)
        assertTrue(!output.contains("repo.maven.apache.org"), output)
        assertTrue(output.contains("PLUGINS artifactory"), output)
    }

    @Test
    fun `artifactory mode without a URL fails with guidance`() {
        val output = project("platform.repositories=artifactory").buildAndFail("help").output
        assertTrue(output.contains("requires platform.artifactory.url"), output)
    }

    @Test
    fun `an unknown mode is rejected`() {
        val output = project("platform.repositories=nexus").buildAndFail("help").output
        assertTrue(output.contains("Unsupported platform.repositories='nexus'"), output)
    }
}
