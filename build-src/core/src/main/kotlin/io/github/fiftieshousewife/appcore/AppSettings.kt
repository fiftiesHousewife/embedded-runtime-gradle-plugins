package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.AuthenticationSupported
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
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
 * both settings plugins, so everything here is safe to run twice. A build that borrows a repository
 * it declares itself (`binaryRepo.repository`) keeps its own Maven and plugin repositories; only the
 * runtime repositories are added.
 */
object AppSettings {
    private const val REPOSITORIES_CONFIGURED = "io.github.fiftieshousewife.appcore.repositories"

    /**
     * Runs [configure] with the binary repository, or null for the public internet, once the
     * settings script has finished: the `embeddedRuntime {}` block ([EmbeddedRuntimeSettings]) and
     * a borrowed repository are both written further down `settings.gradle.kts` than the plugins
     * block that applies this.
     */
    fun withBinaryRepository(settings: Settings, configure: (BinaryRepository?) -> Unit) {
        val inCode = EmbeddedRuntimeSettings.on(settings)
        if (settings.gradle.parent == null) SettingsInCode.reset()
        settings.gradle.settingsEvaluated {
            SettingsInCode.add(inCode.asProperties())
            val borrowed = borrowedRepository(this)
            configure(
                if (borrowed !=
                    null
                ) {
                    BinaryRepository.borrow(this, borrowed)
                } else {
                    BinaryRepository.from(providers)
                },
            )
        }
    }

    /**
     * The repository to borrow: the one named, else one found among those the build declares
     * ([BinaryRepository.discover]). Decided once per build, by whichever settings plugin gets there
     * first, before either adds repositories of its own; recorded as if named, so projects and the
     * toolchain resolver see it too.
     */
    private fun borrowedRepository(settings: Settings): String? {
        val extra = settings.gradle.extensions.extraProperties
        if (extra.has(BORROWED)) return extra.get(BORROWED) as String?
        val providers = settings.providers
        val named = BinaryRepository.borrowedName(providers)
        val borrowed = when {
            named == BinaryRepository.NONE -> null

            named != null -> named

            providers.setting(BinaryRepository.URL) != null -> null

            else -> BinaryRepository.discover(settings)?.also {
                SettingsInCode.add(mapOf(BinaryRepository.REPOSITORY to it))
            }
        }
        extra.set(BORROWED, borrowed)
        return borrowed
    }

    private const val BORROWED = "io.github.fiftieshousewife.appcore.borrowedRepository"

    /** Maven and plugin repositories: configured by whichever settings plugin is applied first. */
    fun configureRepositories(settings: Settings, binaryRepo: BinaryRepository?) {
        val extra = settings.gradle.extensions.extraProperties
        if (extra.has(REPOSITORIES_CONFIGURED)) return
        extra.set(REPOSITORIES_CONFIGURED, true)
        // Only a convention, so a mode the build sets itself wins; without it Gradle's default lets a
        // project that declares any repository lose the runtime repositories added here.
        val drm = settings.dependencyResolutionManagement
        drm.repositoriesMode.convention(RepositoriesMode.PREFER_SETTINGS)
        if (binaryRepo?.borrowedFrom != null) return

        configurePluginRepositories(settings.pluginManagement.repositories, binaryRepo)
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
                        binaryRepo?.let { logIn(this, runtime, it, settings) }
                    }
                }
                filter { includeGroup(runtime.group) }
            }
        }
    }

    private fun logIn(
        repo: IvyArtifactRepository,
        runtime: RuntimeRepository,
        binaryRepo: BinaryRepository,
        settings: Settings,
    ) {
        repo.isAllowInsecureProtocol = binaryRepo.allowInsecureProtocol
        // A file tree takes no login; Gradle refuses credentials for one.
        if (runtime.baseUrl.scheme != "file") authenticateExplicitly(repo, binaryRepo.login(settings.providers).orNull)
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
     * The binary repository's login, for repositories that can't take it by identity: the runtime
     * archives, and every repository of a borrowing build. The values are read at configuration
     * time, so they are stored, encrypted, in the configuration cache entry.
     */
    fun authenticateExplicitly(repo: AuthenticationSupported, login: RepositoryLogin?) {
        login ?: return
        repo.credentials {
            username = login.username
            password = login.password
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
