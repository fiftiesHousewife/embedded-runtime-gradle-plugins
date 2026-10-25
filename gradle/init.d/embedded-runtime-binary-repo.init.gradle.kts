// Corporate bootstrap: install into ~/.gradle/init.d/ on developer machines and CI agents
// that build behind BinaryRepository, alongside these lines in ~/.gradle/gradle.properties:
//
//   binaryRepo.url=https://repo.example.com
//   binaryRepoUsername=<user or service account>
//   binaryRepoPassword=<identity or reference token>
//
// It does two things the java-app / node-app settings plugins cannot do for themselves:
//  1. resolves those settings plugins (and any other settings-level plugin) from BinaryRepository,
//     because a plugin cannot configure the repository it is itself downloaded from;
//  2. redirects builds that do not use them (including the plugins' own build) from Maven
//     Central / the Gradle Plugin Portal to BinaryRepository.
// With binaryRepo.url unset it does nothing, so the same machine can build open-source projects
// against the internet.

import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.authentication.http.BasicAuthentication

val publicHosts = setOf("repo.maven.apache.org", "repo1.maven.org", "plugins.gradle.org")

fun org.gradle.api.artifacts.dsl.RepositoryHandler.binaryRepo(baseUrl: String, repo: String, insecure: Boolean) =
    maven {
        name = "binaryRepo"
        url = uri("${baseUrl.trimEnd('/')}/$repo/")
        isAllowInsecureProtocol = insecure
        credentials(PasswordCredentials::class)
        authentication { create<BasicAuthentication>("basic") }
    }

beforeSettings {
    val baseUrl = providers.gradleProperty("binaryRepo.url").orNull?.takeIf { it.isNotBlank() } ?: return@beforeSettings

    val insecure = providers.gradleProperty("binaryRepo.allowInsecureProtocol").orNull.toBoolean()
    val pluginsRepo = providers.gradleProperty("binaryRepo.repo.gradlePlugins").orNull ?: "gradle-plugins-virtual"
    val mavenRepo = providers.gradleProperty("binaryRepo.repo.maven").orNull ?: "maven-virtual"

    // Runs before settings.gradle.kts, so BinaryRepository is consulted first for settings plugins.
    pluginManagement.repositories.binaryRepo(baseUrl, pluginsRepo, insecure)

    gradle.settingsEvaluated {
        val repositories = dependencyResolutionManagement.repositories
        val publicRepos = repositories.filterIsInstance<MavenArtifactRepository>().filter { it.url.host in publicHosts }
        if (publicRepos.isNotEmpty()) {
            repositories.removeAll(publicRepos.toSet())
            repositories.binaryRepo(baseUrl, mavenRepo, insecure)
        }
        val publicPluginRepos = pluginManagement.repositories.filterIsInstance<MavenArtifactRepository>()
            .filter { it.url.host in publicHosts }
        pluginManagement.repositories.removeAll(publicPluginRepos.toSet())
    }
}
