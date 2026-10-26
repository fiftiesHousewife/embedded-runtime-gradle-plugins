package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A corporate build that already declares its binary repository, with credentials under its own
 * property names: `binaryRepo.repository` names it, and the plugins borrow its server and login.
 */
class BorrowedRepositoryTest {
    @TempDir
    lateinit var tmp: File

    private val server = "https://repo.example.com/artifactory"

    /** Settings declaring [repository] and printing, once evaluated, every repository and its login. */
    private fun probe(repository: String, properties: String): List<String> {
        val project = TestProject(tmp).apply {
            file("gradle.properties", properties)
            file(
                "settings.gradle.kts",
                """
                plugins { id("${JavaAppSettingsPlugin.ID}") }
                rootProject.name = "probe"
                dependencyResolutionManagement { repositories { $repository } }
                gradle.settingsEvaluated {
                    dependencyResolutionManagement.repositories.forEach { repo ->
                        val url = (repo as? org.gradle.api.artifacts.repositories.UrlArtifactRepository)?.url
                        val login = (repo as org.gradle.api.artifacts.repositories.AuthenticationSupported)
                            .getCredentials(PasswordCredentials::class.java)
                        println("REPO ${'$'}{repo.name} ${'$'}url ${'$'}{login.username} ${'$'}{login.password == "s3cret"}")
                    }
                    pluginManagement.repositories.forEach { println("PLUGINS ${'$'}{it.name}") }
                }
                """,
            )
        }
        return project.repositories(project.build("help").output)
    }

    @Test
    fun `credentials under the build's own property names reach the runtime repository, on the same server`() {
        val repos = probe(
            """
            maven {
                name = "artifactory"
                url = uri("$server/maven-virtual")
                credentials {
                    username = providers.gradleProperty("artifactory_user").get()
                    password = providers.gradleProperty("artifactory_token").get()
                }
            }
            """,
            "binaryRepo.repository=artifactory\nartifactory_user=dev\nartifactory_token=s3cret\n",
        )
        assertAll(
            { assertTrue(repos.contains("REPO artifactory $server/maven-virtual dev true"), repos.toString()) },
            { assertTrue(repos.contains("REPO binaryRepoJdks $server/runtimes/ dev true"), repos.toString()) },
            // The build's own repositories, and nothing of the plugins' besides the runtimes.
            { assertEquals(2, repos.count { it.startsWith("REPO ") }, repos.toString()) },
            { assertTrue(repos.none { it.startsWith("PLUGINS binaryRepo") }, repos.toString()) },
        )
    }

    @Test
    fun `credentials Gradle reads by the repository's name are borrowed too, for another runtimes repository`() {
        val repos = probe(
            """
            maven {
                name = "corp"
                url = uri("$server/libs-virtual/")
                credentials(PasswordCredentials::class)
            }
            """,
            "binaryRepo.repository=corp\ncorpUsername=ci\ncorpPassword=s3cret\njavaApp.jdk.binaryRepo=yum-local\n",
        )
        assertTrue(repos.contains("REPO binaryRepoJdks $server/yum-local/ ci true"), repos.toString())
    }

    @Test
    fun `naming a repository the build doesn't declare fails and says which ones it does`() {
        val project = TestProject(tmp).apply {
            file("gradle.properties", "binaryRepo.repository=artifactory\n")
            file(
                "settings.gradle.kts",
                """
                plugins { id("${JavaAppSettingsPlugin.ID}") }
                rootProject.name = "probe"
                dependencyResolutionManagement { repositories { maven { name = "corp"; url = uri("$server/x") } } }
                """,
            )
        }
        val output = project.buildAndFail("help").output
        assertTrue(
            output.contains(
                "binaryRepo.repository=artifactory, but settings.gradle.kts declares no repository named " +
                    "'artifactory' with a URL (declared: corp)",
            ),
            output,
        )
    }

    @Test
    fun `a repository without credentials has no login to lend, and the build says so`() {
        val project = TestProject(tmp).apply {
            file("gradle.properties", "binaryRepo.repository=corp\n")
            file(
                "settings.gradle.kts",
                """
                plugins { id("${JavaAppSettingsPlugin.ID}") }
                rootProject.name = "probe"
                dependencyResolutionManagement { repositories { maven { name = "corp"; url = uri("$server/x") } } }
                """,
            )
        }
        val output = project.buildAndFail("help").output
        assertTrue(output.contains("repository 'corp' has no credentials, so there is no login to borrow"), output)
    }

    @Test
    fun `an app builds with its JDK from the runtimes repository beside the borrowed one`() {
        val serverDir = tmp.resolve("server")
        JdkFixture.publishCurated(serverDir.resolve("runtimes"))
        val project = javaService(
            tmp.resolve("app"),
            JavaServiceOptions(
                publishJdk = false,
                properties = listOf("binaryRepo.repository" to "corp", "corp_user" to "dev", "corp_token" to "s3cret"),
            ),
        )
        project.file("settings.gradle.kts", settingsBorrowing(serverDir.resolve("maven-virtual")))
        assertEquals(TaskOutcome.SUCCESS, project.build("build").task(":appSmokeTest")?.outcome)
    }

    /**
     * Gradle refuses a login for a file: repository it resolves through, so the borrowed one is only
     * declared, under pluginManagement: the plugins take its server and login, and the JDK comes
     * from the file tree beside it, where no login is sent.
     */
    private fun settingsBorrowing(repository: File) =
        """
        pluginManagement {
            repositories {
                maven {
                    name = "corp"
                    url = uri("${repository.toURI()}")
                    credentials {
                        username = providers.gradleProperty("corp_user").get()
                        password = providers.gradleProperty("corp_token").get()
                    }
                }
            }
        }
        plugins { id("${JavaAppSettingsPlugin.ID}") }
        rootProject.name = "svc"
        dependencyResolutionManagement { repositories { mavenCentral() } }
        """
}
