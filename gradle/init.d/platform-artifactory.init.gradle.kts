// Corporate bootstrap: install into ~/.gradle/init.d/ on developer machines and bake into CI
// images that build behind Artifactory, alongside these lines in ~/.gradle/gradle.properties:
//
//   platform.repositories=artifactory
//   platform.artifactory.url=https://artifactory.example.com/artifactory
//   artifactoryUsername=<user or service account>
//   artifactoryPassword=<identity or reference token>
//
// It does two things the settings plugin cannot do for itself:
//  1. resolves the settings plugin (and any other settings-level plugin) from Artifactory, because
//     the plugin cannot configure the repository it is itself downloaded from;
//  2. redirects builds that do not use the settings plugin (including the plugin's own build)
//     from Maven Central / the Gradle Plugin Portal to Artifactory.
// With platform.repositories unset or "public" it does nothing, so the same machine can build
// open-source projects against the internet.

import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.authentication.http.BasicAuthentication

val publicHosts = setOf("repo.maven.apache.org", "repo1.maven.org", "plugins.gradle.org")

fun org.gradle.api.artifacts.dsl.RepositoryHandler.artifactory(baseUrl: String, repo: String, insecure: Boolean) =
    maven {
        name = "artifactory"
        url = uri("${baseUrl.trimEnd('/')}/$repo/")
        isAllowInsecureProtocol = insecure
        credentials(PasswordCredentials::class)
        authentication { create<BasicAuthentication>("basic") }
    }

beforeSettings {
    val mode = providers.gradleProperty("platform.repositories").orNull
    val baseUrl = providers.gradleProperty("platform.artifactory.url").orNull
    if (!mode.equals("artifactory", ignoreCase = true) || baseUrl == null) return@beforeSettings

    val insecure = providers.gradleProperty("platform.artifactory.allowInsecureProtocol").orNull.toBoolean()
    val pluginsRepo = providers.gradleProperty("platform.artifactory.repo.gradlePlugins").orNull ?: "gradle-plugins-virtual"
    val mavenRepo = providers.gradleProperty("platform.artifactory.repo.maven").orNull ?: "maven-virtual"

    // Runs before settings.gradle.kts, so Artifactory is consulted first for settings plugins.
    pluginManagement.repositories.artifactory(baseUrl, pluginsRepo, insecure)

    gradle.settingsEvaluated {
        val repositories = dependencyResolutionManagement.repositories
        val publicRepos = repositories.filterIsInstance<MavenArtifactRepository>().filter { it.url.host in publicHosts }
        if (publicRepos.isNotEmpty()) {
            repositories.removeAll(publicRepos.toSet())
            repositories.artifactory(baseUrl, mavenRepo, insecure)
        }
        val publicPluginRepos = pluginManagement.repositories.filterIsInstance<MavenArtifactRepository>()
            .filter { it.url.host in publicHosts }
        pluginManagement.repositories.removeAll(publicPluginRepos.toSet())
    }
}
