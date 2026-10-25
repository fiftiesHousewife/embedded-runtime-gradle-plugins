package io.github.fiftieshousewife.appcore

import org.gradle.api.provider.ProviderFactory
import java.net.URI

/**
 * A corporate Artifactory that every external input is resolved through: Maven libraries, Gradle
 * plugins, runtime archives, and (for Node) npm packages.
 *
 * Machine configuration, never project configuration: set [URL] once per machine or CI agent in
 * `~/.gradle/gradle.properties` (or as `ORG_GRADLE_PROJECT_...` environment variables) and the same
 * project builds unchanged against Artifactory or, with it unset, the public internet.
 */
data class Artifactory(
    val baseUrl: String,
    val mavenRepo: String,
    val gradlePluginsRepo: String,
    val runtimesRepo: String,
    val releasesRepo: String,
    val snapshotsRepo: String,
    val rpmRepo: String,
    val allowInsecureProtocol: Boolean,
) {
    fun repo(path: String): URI = URI.create("${baseUrl.trimEnd('/')}/${path.trim('/')}/")

    val mavenUrl: URI get() = repo(mavenRepo)
    val gradlePluginsUrl: URI get() = repo(gradlePluginsRepo)
    val runtimesUrl: URI get() = repo(runtimesRepo)
    val rpmUrl: URI get() = repo(rpmRepo)

    fun publishUrl(snapshot: Boolean): URI = repo(if (snapshot) snapshotsRepo else releasesRepo)

    companion object {
        /** Setting this switches every build on the machine to Artifactory. */
        const val URL = "artifactory.url"
        const val MAVEN_REPO = "artifactory.repo.maven"
        const val GRADLE_PLUGINS_REPO = "artifactory.repo.gradlePlugins"
        const val RUNTIMES_REPO = "artifactory.repo.runtimes"
        const val RELEASES_REPO = "artifactory.repo.releases"
        const val SNAPSHOTS_REPO = "artifactory.repo.snapshots"
        const val RPM_REPO = "artifactory.repo.rpm"
        const val ALLOW_INSECURE = "artifactory.allowInsecureProtocol"

        /**
         * Name of the Gradle credentials identity. Gradle reads `artifactoryUsername` and
         * `artifactoryPassword` (an Artifactory identity or reference token) lazily, at execution
         * time, so secrets never land in the configuration cache.
         */
        const val CREDENTIALS_NAME = "artifactory"

        /** The configured instance, or null for the public internet. */
        fun from(providers: ProviderFactory): Artifactory? {
            val url = providers.setting(URL) ?: return null
            return Artifactory(
                baseUrl = url,
                mavenRepo = providers.setting(MAVEN_REPO) ?: "maven-virtual",
                gradlePluginsRepo = providers.setting(GRADLE_PLUGINS_REPO) ?: "gradle-plugins-virtual",
                runtimesRepo = providers.setting(RUNTIMES_REPO) ?: "runtimes",
                releasesRepo = providers.setting(RELEASES_REPO) ?: "libs-release-local",
                snapshotsRepo = providers.setting(SNAPSHOTS_REPO) ?: "libs-snapshot-local",
                rpmRepo = providers.setting(RPM_REPO) ?: "rpm-local",
                allowInsecureProtocol = providers.setting(ALLOW_INSECURE).toBoolean(),
            )
        }
    }
}

/** A Gradle property, with a blank value treated as unset so `-Pname=` clears a machine-wide setting. */
fun ProviderFactory.setting(name: String): String? = gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
