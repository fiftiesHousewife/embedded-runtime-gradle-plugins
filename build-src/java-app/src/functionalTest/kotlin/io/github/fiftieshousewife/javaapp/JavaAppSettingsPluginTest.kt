package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JavaAppSettingsPluginTest {
    @TempDir
    lateinit var tmp: File

    private fun repositories(
        properties: String,
        vararg settingsPlugins: String = arrayOf(JavaAppSettingsPlugin.ID),
    ): List<String> {
        val project =
            TestProject(tmp).apply {
                file("gradle.properties", properties)
                repositoryProbe(*settingsPlugins)
            }
        return project.repositories(project.build("help").output)
    }

    @Test
    fun `without Artifactory it uses Maven Central, the plugin portal and the vendor's own site`() {
        val repos = repositories("")
        assertAll(
            { assertTrue(repos.contains("REPO MavenRepo https://repo.maven.apache.org/maven2/"), repos.toString()) },
            { assertTrue(repos.contains("REPO temurinJdks https://github.com/adoptium/"), repos.toString()) },
            { assertTrue(repos.contains("PLUGINS Gradle Central Plugin Repository"), repos.toString()) },
        )
    }

    @Test
    fun `Artifactory routes everything through the configured instance`() {
        val repos =
            repositories(
                "artifactory.url=https://artifactory.example.com/artifactory\nartifactory.repo.maven=maven-remote",
            )
        assertAll(
            {
                assertTrue(
                    repos.contains("REPO artifactoryJdks https://artifactory.example.com/artifactory/runtimes/"),
                    repos.toString(),
                )
            },
            {
                assertTrue(
                    repos.contains("REPO artifactory https://artifactory.example.com/artifactory/maven-remote/"),
                    repos.toString(),
                )
            },
            { assertTrue(repos.contains("PLUGINS artifactory"), repos.toString()) },
            { assertFalse(repos.any { it.contains("repo.maven.apache.org") }, repos.toString()) },
        )
    }

    @Test
    fun `a build with Java and Node apps can apply both settings plugins`() {
        val repos = repositories("", JavaAppSettingsPlugin.ID, "io.github.fiftieshousewife.node-app.settings")
        assertAll(
            { assertEquals(1, repos.count { it.startsWith("REPO MavenRepo") }, repos.toString()) },
            { assertTrue(repos.any { it.startsWith("REPO temurinJdks") }, repos.toString()) },
            { assertTrue(repos.any { it.startsWith("REPO nodejsDist") }, repos.toString()) },
        )
    }
}
