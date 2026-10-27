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
    fun `without a binary repository it uses Maven Central, the plugin portal and the vendor's own site`() {
        val repos = repositories("")
        assertAll(
            { assertTrue(repos.contains("REPO MavenRepo https://repo.maven.apache.org/maven2/"), repos.toString()) },
            { assertTrue(repos.contains("REPO temurinJdks https://github.com/adoptium/"), repos.toString()) },
            { assertTrue(repos.contains("PLUGINS Gradle Central Plugin Repository"), repos.toString()) },
        )
    }

    @Test
    fun `a binary repository routes everything through the configured instance`() {
        val repos =
            repositories(
                "binaryRepo.url=https://repo.example.com\nbinaryRepo.repo.maven=maven-remote",
            )
        assertAll(
            {
                assertTrue(
                    repos.contains("REPO binaryRepoJdks https://repo.example.com/runtimes/"),
                    repos.toString(),
                )
            },
            {
                assertTrue(
                    repos.contains("REPO binaryRepo https://repo.example.com/maven-remote/"),
                    repos.toString(),
                )
            },
            { assertTrue(repos.contains("PLUGINS binaryRepo"), repos.toString()) },
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

    @Test
    fun `settings set in code win over the same Gradle properties`() {
        val project =
            TestProject(tmp).apply {
                file(
                    "gradle.properties",
                    "binaryRepo.url=https://ignored.example.com\nbinaryRepo.repo.runtimes=ignored\n",
                )
                file("settings.gradle.kts", settingsInCode)
            }
        val repos = project.repositories(project.build("help").output)
        assertAll(
            { assertTrue(repos.contains("REPO binaryRepoJdks https://repo.example.com/jdks/"), repos.toString()) },
            { assertFalse(repos.any { it.contains("ignored") }, repos.toString()) },
        )
    }

    /** Settings that set the binary repository in code, and print the repositories once evaluated. */
    private val settingsInCode =
        """
        plugins { id("${JavaAppSettingsPlugin.ID}") }
        rootProject.name = "probe"
        embeddedRuntime {
            binaryRepository {
                url = "https://repo.example.com"
                runtimesRepo = "jdks"
            }
        }
        gradle.settingsEvaluated {
            dependencyResolutionManagement.repositories.forEach { repo ->
                val url = (repo as? org.gradle.api.artifacts.repositories.UrlArtifactRepository)?.url
                println("REPO ${'$'}{repo.name} ${'$'}url")
            }
        }
        """
}
