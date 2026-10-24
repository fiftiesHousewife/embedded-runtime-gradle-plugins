package io.github.fiftieshousewife.platform.settings

import io.github.fiftieshousewife.platform.PlatformProperties
import io.github.fiftieshousewife.platform.PlatformRepositories
import io.github.fiftieshousewife.platform.PluginVersion
import io.github.fiftieshousewife.platform.RepositoryMode
import io.github.fiftieshousewife.platform.runtime.RuntimeCoordinates
import org.gradle.api.Plugin
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.AuthenticationSupported
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.UrlArtifactRepository
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.api.plugins.ExtensionAware
import org.gradle.authentication.http.BasicAuthentication
import org.gradle.jvm.toolchain.JavaToolchainResolverRegistry
import org.gradle.jvm.toolchain.JvmToolchainManagement
import javax.inject.Inject

/**
 * `io.github.fiftieshousewife.platform.settings` — configures every repository a build needs, for one of two
 * sources selected by the `platform.repositories` Gradle property:
 *
 * | Input                | public                        | artifactory                          |
 * |----------------------|-------------------------------|--------------------------------------|
 * | Maven libraries      | Maven Central                 | `<url>/<repo.maven>`                 |
 * | Project plugins      | Gradle Plugin Portal          | `<url>/<repo.gradlePlugins>`         |
 * | Compile JDKs         | the distribution's own site   | `<url>/<repo.runtimes>/<dist>/...`   |
 * | Bundled JDK / Node   | as above / nodejs.org/dist    | `<url>/<repo.runtimes>/...`          |
 * | npm packages         | registry.npmjs.org            | `<url>/<repo.npm>` (via bundle tasks)|
 *
 * Projects declare no repositories of their own.
 */
abstract class PlatformSettingsPlugin
    @Inject
    constructor(
        private val toolchainResolvers: JavaToolchainResolverRegistry,
    ) : Plugin<Settings> {
        override fun apply(settings: Settings) {
            val repos = PlatformRepositories.from(settings.providers)

            configureToolchains(settings, repos)
            configurePluginRepositories(settings.pluginManagement.repositories, repos)
            alignProjectPluginVersions(settings)
            configureDependencyRepositories(settings, repos)

            settings.gradle.extensions.extraProperties
                .set(APPLIED_MARKER, repos.mode.name)
        }

        private fun configureToolchains(
            settings: Settings,
            repos: PlatformRepositories,
        ) {
            settings.plugins.apply("jvm-toolchain-management")
            toolchainResolvers.register(PlatformJavaToolchainResolver::class.java)

            val jvm = (settings.toolchainManagement as ExtensionAware).extensions.getByType(JvmToolchainManagement::class.java)
            jvm.javaRepositories.repository(repositoryName(repos)) {
                resolverClass.set(PlatformJavaToolchainResolver::class.java)
                if (repos.mode == RepositoryMode.ARTIFACTORY) authenticate(this)
            }
        }

        private fun configurePluginRepositories(
            handler: RepositoryHandler,
            repos: PlatformRepositories,
        ) {
            when (repos.mode) {
                RepositoryMode.PUBLIC -> {
                    handler.gradlePluginPortal()
                }

                RepositoryMode.ARTIFACTORY -> {
                    // The corporate init script usually registered this already, to resolve this plugin.
                    val url = repos.gradlePluginsRepositoryUrl!!
                    val present =
                        handler.filterIsInstance<MavenArtifactRepository>().any {
                            it.url.toString().trimEnd('/') ==
                                url.toString().trimEnd('/')
                        }
                    if (!present) {
                        handler.maven {
                            name = PlatformProperties.CREDENTIALS_NAME
                            this.url = url
                            secure(this, repos)
                            authenticate(this)
                        }
                    }
                }
            }
        }

        /**
         * Project plugins from this jar are resolved at the settings plugin's own version, so build
         * scripts write `id("io.github.fiftieshousewife.platform.java-bundle")` with no version and can never drift
         * from the settings plugin.
         */
        private fun alignProjectPluginVersions(settings: Settings) {
            val version = PluginVersion.current
            settings.pluginManagement.plugins {
                PROJECT_PLUGIN_IDS.forEach { id(it).version(version) }
            }
        }

        private fun configureDependencyRepositories(
            settings: Settings,
            repos: PlatformRepositories,
        ) {
            val drm = settings.dependencyResolutionManagement
            drm.repositoriesMode.convention(RepositoriesMode.PREFER_SETTINGS)

            drm.repositories {
                RuntimeCoordinates.repositories(repos).forEach { runtime ->
                    exclusiveContent {
                        forRepository {
                            ivy {
                                name = runtime.name
                                url = runtime.baseUrl
                                patternLayout { artifact(runtime.artifactPattern) }
                                // Plain file tree: no ivy.xml, the artifact itself is the module.
                                metadataSources { artifact() }
                                secure(this, repos)
                                if (repos.mode == RepositoryMode.ARTIFACTORY) authenticateExplicitly(this, settings)
                            }
                        }
                        filter { runtime.groups.forEach(::includeGroup) }
                    }
                }

                when (repos.mode) {
                    RepositoryMode.PUBLIC -> {
                        mavenCentral()
                    }

                    RepositoryMode.ARTIFACTORY -> {
                        maven {
                            name = PlatformProperties.CREDENTIALS_NAME
                            url = repos.mavenRepositoryUrl!!
                            secure(this, repos)
                            authenticate(this)
                        }
                    }
                }
            }
        }

        private fun repositoryName(repos: PlatformRepositories) =
            if (repos.mode == RepositoryMode.ARTIFACTORY) PlatformProperties.CREDENTIALS_NAME else "platformPublic"

        companion object {
            const val APPLIED_MARKER = "io.github.fiftieshousewife.platform.settings.mode"
            val PROJECT_PLUGIN_IDS =
                listOf("io.github.fiftieshousewife.platform.java-bundle", "io.github.fiftieshousewife.platform.node-bundle")

            /**
             * Gradle resolves `artifactoryUsername` / `artifactoryPassword` lazily from the repository
             * name, keeping them out of the configuration cache. The name must be `artifactory`, and
             * Gradle makes names unique within one repository container, so this is used for at most
             * one repository per container.
             */
            internal fun authenticate(repo: AuthenticationSupported) {
                repo.credentials(PasswordCredentials::class.java)
                repo.authentication { create("basic", BasicAuthentication::class.java) }
            }

            /**
             * Same credentials, for a second repository in the same container (the runtime archives).
             * The values are read at configuration time, so they are stored, encrypted, in the
             * configuration cache entry.
             */
            internal fun authenticateExplicitly(
                repo: AuthenticationSupported,
                settings: Settings,
            ) {
                val user = settings.providers.gradleProperty("${PlatformProperties.CREDENTIALS_NAME}Username")
                val password = settings.providers.gradleProperty("${PlatformProperties.CREDENTIALS_NAME}Password")
                repo.credentials {
                    username = user.orNull
                    this.password = password.orNull
                }
                repo.authentication { create("basic", BasicAuthentication::class.java) }
            }

            internal fun secure(
                repo: UrlArtifactRepository,
                repos: PlatformRepositories,
            ) {
                repo.isAllowInsecureProtocol = repos.allowInsecureProtocol
            }
        }
    }
