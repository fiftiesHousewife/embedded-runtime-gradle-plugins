package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.AuthenticationSupported
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.artifacts.repositories.UrlArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.io.Serializable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * A corporate binary repository every external input is resolved through, never with secrets in a
 * project: **borrowed** ([REPOSITORY]) from one the build declares, with its server and login, or
 * **by URL** ([URL]) per machine, with `binaryRepoUsername`/`binaryRepoPassword`.
 */
data class BinaryRepository(
    val baseUrl: String,
    val mavenRepo: String,
    val gradlePluginsRepo: String,
    val runtimesRepo: String,
    val releasesRepo: String,
    val snapshotsRepo: String,
    val allowInsecureProtocol: Boolean,
    val borrowedFrom: String? = null,
    private val borrowedLogin: RepositoryLogin? = null,
) {
    fun repo(path: String): URI = URI.create("${baseUrl.trimEnd('/')}/${path.trim('/')}/")

    val mavenUrl: URI get() = repo(mavenRepo)
    val gradlePluginsUrl: URI get() = repo(gradlePluginsRepo)
    val runtimesUrl: URI get() = repo(runtimesRepo)

    fun publishUrl(snapshot: Boolean): URI = repo(if (snapshot) snapshotsRepo else releasesRepo)

    /** The login for every repository on the server: the borrowed repository's, else `binaryRepoUsername`. */
    fun login(providers: ProviderFactory): Provider<RepositoryLogin> =
        if (borrowedFrom != null) providers.provider { borrowedLogin } else identityLogin(providers)

    companion object {
        const val URL = "binaryRepo.url"

        /** A repository the build declares, whose server and login the plugins use; found when unset ([discover]). */
        const val REPOSITORY = "binaryRepo.repository"

        const val NONE = "none"

        private val PUBLIC_HOSTS = listOf(
            "repo.maven.apache.org", "repo1.maven.org", "central.sonatype.com", "oss.sonatype.org",
            "s01.oss.sonatype.org", "plugins.gradle.org", "plugins-artifacts.gradle.org", "repo.gradle.org",
            "maven.google.com", "dl.google.com", "jitpack.io", "jcenter.bintray.com",
            "nodejs.org",
        )

        /** A public repository is never taken for the organisation's own. */
        internal fun isPublic(url: URI) = PUBLIC_HOSTS.any { url.host == it || url.host.endsWith(".$it") }

        const val MAVEN_REPO = "binaryRepo.repo.maven"
        const val GRADLE_PLUGINS_REPO = "binaryRepo.repo.gradlePlugins"
        const val RUNTIMES_REPO = "binaryRepo.repo.runtimes"
        const val RELEASES_REPO = "binaryRepo.repo.releases"
        const val SNAPSHOTS_REPO = "binaryRepo.repo.snapshots"
        const val ALLOW_INSECURE = "binaryRepo.allowInsecureProtocol"

        /** Gradle reads `binaryRepoUsername`/`Password` at execution: secrets never reach the configuration cache. */
        const val CREDENTIALS_NAME = "binaryRepo"

        /** By name: the JDK toolchain resolver, which Gradle creates itself, can't see the settings. */
        private val borrowed = ConcurrentHashMap<String, BinaryRepository>()

        fun from(providers: ProviderFactory): BinaryRepository? {
            val name = providers.setting(REPOSITORY)?.takeUnless { it == NONE }
            return if (name != null) {
                borrowed[name] ?: throw GradleException(
                    "$REPOSITORY=$name, but no settings plugin has found that repository yet: apply " +
                        "io.github.fiftieshousewife.java-app.settings or .node-app.settings in settings.gradle.kts",
                )
            } else {
                providers.setting(URL)?.let { of(providers, it, providers.setting(ALLOW_INSECURE).toBoolean()) }
            }
        }

        fun borrowedName(providers: ProviderFactory): String? = providers.setting(REPOSITORY)

        /** Several repositories on one server count as one; repositories on several servers must be named. */
        fun discover(settings: Settings): String? {
            val own = (settings.dependencyResolutionManagement.repositories + settings.pluginManagement.repositories)
                .filterIsInstance<UrlArtifactRepository>()
                .filter { repo ->
                    val url = repo.url
                    url.scheme in setOf("http", "https") && !isPublic(url)
                }
            val servers = own.groupBy { URI.create(it.url.toString().trimEnd('/') + "/").resolve("..") }
            if (servers.size > 1) {
                throw GradleException(
                    "settings.gradle.kts declares repositories on several servers (" +
                        own.joinToString { "${(it as ArtifactRepository).name} at ${it.url}" } +
                        "): set $REPOSITORY to the one whose server and login hold the runtimes, or to $NONE",
                )
            }
            return own.firstOrNull()?.let { (it as ArtifactRepository).name }
        }

        /** The others are siblings: `…/artifactory/maven-virtual/` puts runtimes at `…/artifactory/runtimes/`. */
        fun borrow(settings: Settings, name: String): BinaryRepository {
            val declared = settings.dependencyResolutionManagement.repositories + settings.pluginManagement.repositories
            val repository =
                declared.filterIsInstance<UrlArtifactRepository>().firstOrNull {
                    (it as ArtifactRepository).name == name
                }
                    ?: throw undeclared(name, declared.map { it.name })
            val server = URI.create(repository.url.toString().trimEnd('/') + "/").resolve("..").toString()
            val login = (repository as? AuthenticationSupported)?.let { loginOf(name, it) }
            return of(settings.providers, server, repository.isAllowInsecureProtocol)
                .copy(borrowedFrom = name, borrowedLogin = login)
                .also { borrowed[name] = it }
        }

        private fun undeclared(name: String, declared: List<String>) = GradleException(
            "$REPOSITORY=$name, but settings.gradle.kts declares no repository named '$name' with a URL " +
                "(declared: ${declared.joinToString().ifEmpty { "none" }}). Name the repository your build already " +
                "uses, e.g. maven { name = \"$name\"; url = uri(\"https://…\"); credentials { … } }",
        )

        /** Asking a repository without credentials for them gives it empty ones, which breaks it: stop instead. */
        private fun loginOf(name: String, repository: AuthenticationSupported): RepositoryLogin {
            val credentials =
                try {
                    repository.getCredentials(PasswordCredentials::class.java)
                } catch (e: IllegalArgumentException) {
                    throw GradleException(
                        "$REPOSITORY=$name, but repository '$name' logs in with other credentials than a user name " +
                            "and password (${e.message}); give it credentials { username = …; password = … }",
                        e,
                    )
                }
            val user = credentials.username ?: throw GradleException(
                "$REPOSITORY=$name, but repository '$name' has no credentials, so there is no login to borrow. " +
                    "Name the repository that logs in, or use $URL for a server that needs none.",
            )
            return RepositoryLogin(user, credentials.password.orEmpty())
        }

        fun identityLogin(providers: ProviderFactory): Provider<RepositoryLogin> =
            providers.gradleProperty("${CREDENTIALS_NAME}Username")
                .zip(providers.gradleProperty("${CREDENTIALS_NAME}Password").orElse("")) { user, password ->
                    RepositoryLogin(user, password)
                }

        private fun of(providers: ProviderFactory, url: String, allowInsecureProtocol: Boolean) = BinaryRepository(
            baseUrl = url,
            mavenRepo = providers.setting(MAVEN_REPO) ?: "maven-virtual",
            gradlePluginsRepo = providers.setting(GRADLE_PLUGINS_REPO) ?: "gradle-plugins-virtual",
            runtimesRepo = providers.setting(RUNTIMES_REPO) ?: "runtimes",
            releasesRepo = providers.setting(RELEASES_REPO) ?: "libs-release-local",
            snapshotsRepo = providers.setting(SNAPSHOTS_REPO) ?: "libs-snapshot-local",
            allowInsecureProtocol = allowInsecureProtocol,
        )
    }
}

/** A user name and password or token for the binary repository; the password is never printed. */
data class RepositoryLogin(val username: String, val password: String) : Serializable {
    override fun toString() = "RepositoryLogin($username, ****)"

    private companion object {
        private const val serialVersionUID = 1L
    }
}
