package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A build's own repository, found and borrowed without `binaryRepo.repository` naming it. */
class DiscoveredRepositoryTest {
    @TempDir
    lateinit var tmp: File

    private val server = "https://repo.example.com/artifactory"

    private fun probe(repository: String, properties: String) = probeRepositories(tmp, repository, properties)

    private val artifactory =
        """
        maven {
            name = "artifactory"
            url = uri("$server/maven-virtual")
            credentials {
                username = providers.gradleProperty("artifactory_user").get()
                password = providers.gradleProperty("artifactory_token").get()
            }
        }
        mavenCentral()
        """
    private val login = "artifactory_user=dev\nartifactory_token=s3cret\n"

    @Test
    fun `the build's own repository is found without being named, and public ones are passed over`() {
        val repos = probe(artifactory, login)
        assertTrue(repos.contains("REPO binaryRepoJdks $server/runtimes/ dev true"), repos.toString())
    }

    @Test
    fun `none turns finding it off`() {
        val repos = probe(artifactory, login + "binaryRepo.repository=none\n")
        assertTrue(repos.none { it.startsWith("REPO binaryRepoJdks") }, repos.toString())
    }

    @Test
    fun `repositories on several servers must be named`() {
        val project = TestProject(tmp).apply {
            file("gradle.properties", login)
            file(
                "settings.gradle.kts",
                """
                plugins { id("${JavaAppSettingsPlugin.ID}") }
                rootProject.name = "probe"
                dependencyResolutionManagement {
                    repositories { $artifactory
                        maven { name = "other"; url = uri("https://nexus.example.org/repository/releases") }
                    }
                }
                """,
            )
        }
        val output = project.buildAndFail("help").output
        assertTrue(output.contains("declares repositories on several servers") && output.contains("other at"), output)
    }
}
