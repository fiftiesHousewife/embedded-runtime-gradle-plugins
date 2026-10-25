package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.AuthenticationSupported
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.authentication.http.BasicAuthentication

/**
 * The `settings.gradle.kts` wiring the Java and Node settings plugins share:
 *
 * | Input            | public                | binaryRepo                       |
 * |------------------|-----------------------|-----------------------------------|
 * | Maven libraries  | Maven Central         | `<url>/<repo.maven>`              |
 * | Project plugins  | Gradle Plugin Portal  | `<url>/<repo.gradlePlugins>`      |
 * | Runtime archives | the vendor's own site | `<url>/<repo.runtimes>/...`       |
 *
 * Projects declare no repositories of their own. A build holding both Java and Node apps applies
 * both settings plugins, so everything here is safe to run twice.
 */
object AppSettings {
    private const val REPOSITORIES_CONFIGURED = "io.github.fiftieshousewife.appcore.repositories"

    /** Maven and plugin repositories: configured by whichever settings plugin is applied first. */
    fun configureRepositories(settings: Settings, binaryRepo: BinaryRepository?) {
        val extra = settings.gradle.extensions.extraProperties
        if (extra.has(REPOSITORIES_CONFIGURED)) return
        extra.set(REPOSITORIES_CONFIGURED, true)

        configurePluginRepositories(settings.pluginManagement.repositories, binaryRepo)
        val drm = settings.dependencyResolutionManagement
        drm.repositoriesMode.convention(RepositoriesMode.PREFER_SETTINGS)
        drm.repositories {
            if (binaryRepo == null) {
                mavenCentral()
            } else {
                maven {
                    name = BinaryRepository.CREDENTIALS_NAME
                    url = binaryRepo.mavenUrl
                    isAllowInsecureProtocol = binaryRepo.allowInsecureProtocol
                    authenticate(this)
                }
            }
        }
    }

    /** An Ivy repository serving exactly one runtime's archives, and nothing else. */
    fun addRuntimeRepository(settings: Settings, runtime: RuntimeRepository, binaryRepo: BinaryRepository?) {
        settings.dependencyResolutionManagement.repositories {
            exclusiveContent {
                forRepository {
                    ivy {
                        name = runtime.name
                        url = runtime.baseUrl
                        patternLayout { artifact(runtime.artifactPattern) }
                        // Plain file tree: no ivy.xml, the artifact itself is the module.
                        metadataSources { artifact() }
                        if (binaryRepo != null) {
                            isAllowInsecureProtocol = binaryRepo.allowInsecureProtocol
                            authenticateExplicitly(this, settings)
                        }
                    }
                }
                filter { includeGroup(runtime.group) }
            }
        }
    }

    /**
     * Project plugins are resolved at the settings plugin's own version, so build scripts write
     * `id("io.github.fiftieshousewife.java-app")` with no version and can never drift from it.
     */
    fun alignPluginVersions(settings: Settings, vararg pluginIds: String) {
        val version = PluginVersion.current
        settings.pluginManagement.plugins {
            pluginIds.forEach { id(it).version(version) }
        }
    }

    /** Records that [settingsPluginId] ran, for [requireApplied]. */
    fun markApplied(settings: Settings, settingsPluginId: String) = settings.gradle.extensions.extraProperties
        .set(settingsPluginId, true)

    /** Fails a project plugin early, with the line to add, when its settings plugin is missing. */
    fun requireApplied(project: Project, settingsPluginId: String) {
        if (!project.gradle.extensions.extraProperties
                .has(settingsPluginId)
        ) {
            throw GradleException(
                "Apply the settings plugin in settings.gradle.kts first:\n" +
                    "  plugins { id(\"$settingsPluginId\") version \"<version>\" }\n" +
                    "It configures the Maven, plugin and runtime repositories this plugin resolves from.",
            )
        }
    }

    /**
     * Gradle resolves `binaryRepoUsername` / `binaryRepoPassword` lazily from the repository
     * name, keeping them out of the configuration cache. The name must be `binaryRepo`, and Gradle
     * makes names unique within one repository container, so this is used for at most one
     * repository per container.
     */
    fun authenticate(repo: AuthenticationSupported) {
        repo.credentials(PasswordCredentials::class.java)
        repo.authentication { create("basic", BasicAuthentication::class.java) }
    }

    /**
     * Same credentials, for further repositories in the same container (the runtime archives).
     * The values are read at configuration time, so they are stored, encrypted, in the
     * configuration cache entry.
     */
    private fun authenticateExplicitly(repo: AuthenticationSupported, settings: Settings) {
        val user = settings.providers.gradleProperty("${BinaryRepository.CREDENTIALS_NAME}Username")
        val password = settings.providers.gradleProperty("${BinaryRepository.CREDENTIALS_NAME}Password")
        repo.credentials {
            username = user.orNull
            this.password = password.orNull
        }
        repo.authentication { create("basic", BasicAuthentication::class.java) }
    }

    private fun configurePluginRepositories(handler: RepositoryHandler, binaryRepo: BinaryRepository?) {
        if (binaryRepo == null) {
            handler.gradlePluginPortal()
            return
        }
        // The corporate init script usually registered this already, to resolve this very plugin.
        val url = binaryRepo.gradlePluginsUrl
        val present =
            handler.filterIsInstance<MavenArtifactRepository>().any {
                it.url.toString().trimEnd('/') == url.toString().trimEnd('/')
            }
        if (!present) {
            handler.maven {
                name = BinaryRepository.CREDENTIALS_NAME
                this.url = url
                isAllowInsecureProtocol = binaryRepo.allowInsecureProtocol
                authenticate(this)
            }
        }
    }
}
