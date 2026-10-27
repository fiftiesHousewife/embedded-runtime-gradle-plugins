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
 * A corporate binary repository (Artifactory, Nexus, …) that every external input is resolved
 * through: Maven libraries, Gradle plugins, runtime archives, and (for Node) npm packages.
 *
 * Configured one of two ways, never with secrets in a project:
 *
 * - **Borrowed** ([REPOSITORY]): the build already declares a repository on the server, with
 *   credentials under whatever names its developers use. The plugins leave the build's own
 *   repositories alone, and use that one's server and credentials for the runtime archives,
 *   publishing, npm and uploads; the other repositories are its siblings on the same server.
 * - **By URL** ([URL]): set once per machine or CI agent, with the login in `binaryRepoUsername` /
 *   `binaryRepoPassword`; the plugins then declare every repository themselves.
 */
data class BinaryRepository(
    val baseUrl: String,
    val mavenRepo: String,
    val gradlePluginsRepo: String,
    val runtimesRepo: String,
    val releasesRepo: String,
    val snapshotsRepo: String,
    val rpmRepo: String,
    val allowInsecureProtocol: Boolean,
    /** The declared repository this one was borrowed from, or null when configured by [URL]. */
    val borrowedFrom: String? = null,
    private val borrowedLogin: RepositoryLogin? = null,
) {
    fun repo(path: String): URI = URI.create("${baseUrl.trimEnd('/')}/${path.trim('/')}/")

    val mavenUrl: URI get() = repo(mavenRepo)
    val gradlePluginsUrl: URI get() = repo(gradlePluginsRepo)
    val runtimesUrl: URI get() = repo(runtimesRepo)
    val rpmUrl: URI get() = repo(rpmRepo)

    fun publishUrl(snapshot: Boolean): URI = repo(if (snapshot) snapshotsRepo else releasesRepo)

    /** The login for every repository on the server: the borrowed repository's, else `binaryRepoUsername`. */
    fun login(providers: ProviderFactory): Provider<RepositoryLogin> =
        if (borrowedFrom != null) providers.provider { borrowedLogin } else identityLogin(providers)

    companion object {
        /** Setting this switches every build on the machine to the binary repository. */
        const val URL = "binaryRepo.url"

        /**
         * The name of a repository the build declares, whose server and credentials the plugins use.
         * Unset, it is found ([discover]); [NONE] turns that off.
         */
        const val REPOSITORY = "binaryRepo.repository"

        /** `binaryRepo.repository=none`: borrow nothing, even when a repository could be found. */
        const val NONE = "none"

        /** Hosts of public repositories, which are never taken for the organisation's own. */
        private val PUBLIC_HOSTS = listOf(
            "repo.maven.apache.org", "repo1.maven.org", "central.sonatype.com", "oss.sonatype.org",
            "s01.oss.sonatype.org", "plugins.gradle.org", "plugins-artifacts.gradle.org", "repo.gradle.org",
            "maven.google.com", "dl.google.com", "jitpack.io", "jcenter.bintray.com",
        )
        const val MAVEN_REPO = "binaryRepo.repo.maven"
        const val GRADLE_PLUGINS_REPO = "binaryRepo.repo.gradlePlugins"
        const val RUNTIMES_REPO = "binaryRepo.repo.runtimes"
        const val RELEASES_REPO = "binaryRepo.repo.releases"
        const val SNAPSHOTS_REPO = "binaryRepo.repo.snapshots"
        const val RPM_REPO = "binaryRepo.repo.rpm"
        const val ALLOW_INSECURE = "binaryRepo.allowInsecureProtocol"

        /**
         * Name of the Gradle credentials identity when configured by [URL]. Gradle reads
         * `binaryRepoUsername` and `binaryRepoPassword` (an Artifactory identity or reference token)
         * lazily, at execution time, so secrets never land in the configuration cache.
         */
        const val CREDENTIALS_NAME = "binaryRepo"

        /**
         * Borrowed repositories, recorded by the settings plugins once the settings script has
         * declared them, by name. Kept here because the JDK toolchain resolver, which Gradle creates
         * itself, can't see the settings; a daemon runs one build at a time.
         */
        private val borrowed = ConcurrentHashMap<String, BinaryRepository>()

        /** The configured instance, or null for the public internet. */
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

        /** The repository named by [REPOSITORY], which the settings plugins wait for the settings script to declare. */
        fun borrowedName(providers: ProviderFactory): String? = providers.setting(REPOSITORY)

        /**
         * The repository to borrow when none is named and no [URL] is set: among those [settings]
         * declares, the ones on a server of the organisation's own (not a public host, not a local
         * `file:` tree). Several on one server (a `maven-virtual` and a `gradle-plugins-virtual`) are
         * one server, and the first is taken; repositories on several servers must be named.
         */
        fun discover(settings: Settings): String? {
            val own = (settings.dependencyResolutionManagement.repositories + settings.pluginManagement.repositories)
                .filterIsInstance<UrlArtifactRepository>()
                .filter { repo ->
                    val url = repo.url
                    url.scheme in setOf("http", "https") &&
                        PUBLIC_HOSTS.none { url.host == it || url.host.endsWith(".$it") }
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

        /**
         * Finds the repository named [REPOSITORY] among the ones [settings] declares, and records it.
         * The other repositories are its siblings: `…/artifactory/maven-virtual/` gives the server
         * `…/artifactory/` and runtimes at `…/artifactory/<binaryRepo.repo.runtimes>/`.
         */
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

        /**
         * The repository's own login, whichever way the build gave it: `credentials { … }`, or
         * `credentials(PasswordCredentials::class)` read by the repository's name. Borrowing is for
         * a repository that logs in, so one without a user name stops the build: asking a
         * repository for credentials it doesn't have gives it empty ones, which would break it, and
         * it would have nothing to lend anyway (set `binaryRepo.url` to use a server anonymously).
         */
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

        /** `binaryRepoUsername` / `binaryRepoPassword`, read when first needed. */
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
            rpmRepo = providers.setting(RPM_REPO) ?: "rpm-local",
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

/**
 * A setting: set in `settings.gradle.kts`'s `embeddedRuntime {}` block ([EmbeddedRuntimeSettings]),
 * else the Gradle property of that name. A blank value counts as unset, so `-Pname=` (or setting it
 * to "") clears a machine-wide setting.
 */
fun ProviderFactory.setting(name: String): String? =
    (SettingsInCode[name] ?: gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }

/** Every setting whose name starts with [prefix], set in code or as a property; the code's win. */
fun ProviderFactory.settingsPrefixedBy(prefix: String): Map<String, String> =
    (gradlePropertiesPrefixedBy(prefix).get() + SettingsInCode.prefixed(prefix)).filterValues { it.isNotBlank() }

/**
 * The values `embeddedRuntime {}` set, by property name, recorded once the settings script has run.
 * Kept here because the JDK toolchain resolver, which Gradle creates itself, can't see the settings;
 * a daemon runs one build at a time, and the root build's settings plugin starts each build afresh.
 */
internal object SettingsInCode {
    @Volatile
    private var values: Map<String, String> = emptyMap()

    operator fun get(name: String): String? = values[name]

    fun prefixed(prefix: String): Map<String, String> = values.filterKeys { it.startsWith(prefix) }

    fun reset() {
        values = emptyMap()
    }

    fun add(more: Map<String, String>) {
        values = values + more
    }
}
