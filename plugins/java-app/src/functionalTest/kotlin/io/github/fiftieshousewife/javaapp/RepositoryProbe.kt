package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import java.io.File

/**
 * Builds settings in [dir] that declare [repository], with [properties], and returns every
 * repository they end up with, once evaluated, and its login: `REPO <name> <url> <user> <password ok>`.
 */
fun probeRepositories(dir: File, repository: String, properties: String): List<String> {
    val project = TestProject(dir).apply {
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
