package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JavaAppSettingsPluginTest {
    @TempDir
    lateinit var tmp: File

    private fun project(
        properties: String,
        vararg settingsPlugins: String = arrayOf(JavaAppSettingsPlugin.ID),
    ): TestProject =
        TestProject(tmp).apply {
            file("gradle.properties", properties)
            file(
                "settings.gradle.kts",
                """
            plugins { ${settingsPlugins.joinToString("; ") { "id(\"$it\")" }} }
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
    fun `without artifactory it uses Maven Central, the plugin portal and the vendor's own site`() {
        val output = project("").build("help").output

        assertTrue(output.contains("REPO MavenRepo https://repo.maven.apache.org/maven2/"), output)
        assertTrue(output.contains("REPO temurinJdks https://github.com/adoptium/"), output)
        assertTrue(output.contains("PLUGINS Gradle Central Plugin Repository"), output)
    }

    @Test
    fun `artifactory routes everything through the configured instance`() {
        val output =
            project(
                """
            artifactory.url=https://artifactory.example.com/artifactory
            artifactory.repo.maven=maven-remote
            """,
            ).build("help").output

        assertTrue(output.contains("REPO artifactoryJdks https://artifactory.example.com/artifactory/runtimes/"), output)
        assertTrue(output.contains("REPO artifactory https://artifactory.example.com/artifactory/maven-remote/"), output)
        assertTrue(!output.contains("repo.maven.apache.org"), output)
        assertTrue(output.contains("PLUGINS artifactory"), output)
    }

    @Test
    fun `a build with Java and Node apps can apply both settings plugins`() {
        val output =
            project("", JavaAppSettingsPlugin.ID, "io.github.fiftieshousewife.node-app.settings").build("help").output
        val repos = output.lines().filter { it.startsWith("REPO ") }

        assertEquals(1, repos.count { it.startsWith("REPO MavenRepo") }, output)
        assertTrue(repos.any { it.startsWith("REPO temurinJdks") }, output)
        assertTrue(repos.any { it.startsWith("REPO nodejsDist") }, output)
    }
}
