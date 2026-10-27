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
 * A build with Java and Node apps applies both settings plugins, so all of this is safe to run twice. A build
 * that borrows its own repository (`binaryRepo.repository`) keeps its Maven and plugin repositories.
 */
object AppSettings {
    private const val REPOSITORIES_CONFIGURED = "io.github.fiftieshousewife.appcore.repositories"

    /**
     * Waits for the settings script: the `embeddedRuntime {}` block ([EmbeddedRuntimeSettings]) and a borrowed
     * repository are written further down `settings.gradle.kts` than the plugins block that applies this.
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

    /** Decided once per build and recorded as if named, so projects and the toolchain resolver see it too. */
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

    /** So build scripts give no version and can never drift from the settings plugin's. */
    fun alignPluginVersions(settings: Settings, vararg pluginIds: String) {
        val version = PluginVersion.current
        settings.pluginManagement.plugins {
            pluginIds.forEach { id(it).version(version) }
        }
    }

    fun markApplied(settings: Settings, settingsPluginId: String) = settings.gradle.extensions.extraProperties
        .set(settingsPluginId, true)

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
     * Gradle reads `binaryRepoUsername`/`binaryRepoPassword` lazily by the name `binaryRepo`, keeping them out
     * of the configuration cache; names are unique per container, so this serves one repository in each.
     */
    fun authenticate(repo: AuthenticationSupported) {
        repo.credentials(PasswordCredentials::class.java)
        repo.authentication { create("basic", BasicAuthentication::class.java) }
    }

    /**
     * For repositories that can't take the login by name (runtime archives, a borrowing build's): read at
     * configuration time, so it is stored, encrypted, in the configuration cache entry.
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
