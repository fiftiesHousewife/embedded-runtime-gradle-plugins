package io.github.fiftieshousewife.platform.bundle

import io.github.fiftieshousewife.platform.PlatformProperties
import io.github.fiftieshousewife.platform.PlatformRepositories
import io.github.fiftieshousewife.platform.bundle.tasks.BundleSmokeTest
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateSbom
import io.github.fiftieshousewife.platform.bundle.tasks.VerifyPublishable
import io.github.fiftieshousewife.platform.runtime.Arch
import io.github.fiftieshousewife.platform.runtime.Os
import io.github.fiftieshousewife.platform.runtime.Platform
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Tar
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.plugins.signing.SigningExtension

/**
 * Publishes the bundle: a Maven publication of a real software component plus its SBOM, the
 * corporate repository when one is configured, opt-in signing, and the guard that refuses to
 * publish a bundle nobody has started.
 */
internal class BundlePublishing(
    private val project: Project,
    private val extension: BundleExtension,
    private val components: SoftwareComponentFactory,
    private val repos: PlatformRepositories,
    private val target: Provider<Platform>,
    private val cross: Provider<Boolean>,
) {
    fun register(
        tar: TaskProvider<Tar>,
        sbom: TaskProvider<GenerateSbom>,
        smoke: TaskProvider<BundleSmokeTest>,
    ) {
        project.pluginManager.apply("maven-publish")
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val platformId = target.map { it.id }

        publishing.publications.register("bundle", MavenPublication::class.java) {
            artifactId = extension.appName.get()
            from(bundleComponent(tar))
            artifact(sbom.flatMap { it.outputFile }) {
                classifier = "${platformId.get()}-cyclonedx"
                extension = "json"
                builtBy(sbom)
            }
            pom {
                // Every artifact carries a platform classifier, so there is no main artifact.
                packaging = "pom"
                name.set(extension.appName)
                description.set("Self-contained ${extension.appName.get()} bundle (runtime, libraries, application)")
            }
        }

        // The publish URL depends on whether the version is a snapshot, which build scripts set
        // after plugins are applied; this is the one place an afterEvaluate is warranted.
        project.afterEvaluate {
            val url = repos.publishRepositoryUrl(project.version.toString().endsWith("-SNAPSHOT")) ?: return@afterEvaluate
            publishing.repositories.maven {
                name = PlatformProperties.CREDENTIALS_NAME
                this.url = url
                isAllowInsecureProtocol = repos.allowInsecureProtocol
                credentials(PasswordCredentials::class.java)
            }
        }

        project.providers.gradleProperty("signingKey").orNull?.let { key ->
            project.pluginManager.apply("signing")
            project.extensions.getByType(SigningExtension::class.java).apply {
                useInMemoryPgpKeys(key, project.providers.gradleProperty("signingPassword").orNull)
                sign(publishing.publications.getByName("bundle"))
            }
        }

        val guard =
            project.tasks.register("bundleVerifyPublishable", VerifyPublishable::class.java) {
                group = BundleWiring.VERIFICATION_GROUP
                description = "Fails publication of bundles that were not smoke-tested on this machine"
                crossBuild.set(cross)
                smokeTestEnabled.set(extension.smokeTestEnabled)
                allowUntested.set(extension.allowUntestedPublish)
                targetPlatform.set(platformId)
            }
        project.tasks.withType(PublishToMavenRepository::class.java).configureEach { dependsOn(guard, smoke) }
        project.tasks.withType(PublishToMavenLocal::class.java).configureEach { dependsOn(smoke) }
    }

    /**
     * A real software component rather than artifacts bolted onto a bare publication: it carries
     * the target's OS and architecture as Gradle's own standard attributes, so the published Gradle
     * Module Metadata describes which machine the bundle is for and a consuming Gradle build can
     * select it instead of naming a classifier by hand. Maven-only consumers are unaffected — the
     * classifier is still there.
     *
     * One build produces one platform, so each platform build publishes its own module metadata.
     * Publishing several platforms to one coordinate therefore needs either distinct artifactIds or
     * module metadata turned off; see the README.
     */
    private fun bundleComponent(tar: TaskProvider<Tar>): SoftwareComponent {
        val objects = project.objects
        val bundleElements =
            project.configurations.consumable("bundleElements") {
                description = "The assembled bundle archive for one target platform"
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, BUNDLE_CATEGORY))
                    // The runtime and every dependency are inside the archive.
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EMBEDDED))
                    attributeProvider(
                        OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                        target.map { objects.named(OperatingSystemFamily::class.java, osFamilyOf(it)) },
                    )
                    attributeProvider(
                        MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                        target.map { objects.named(MachineArchitecture::class.java, architectureOf(it)) },
                    )
                }
                outgoing.artifact(tar)
            }
        return components.adhoc("bundle").apply {
            addVariantsFromConfiguration(bundleElements.get()) { mapToMavenScope("runtime") }
        }
    }

    companion object {
        /** Gradle defines no standard category for a packaged application, so the bundle names one. */
        const val BUNDLE_CATEGORY = "platform-bundle"

        fun osFamilyOf(platform: Platform) =
            when (platform.os) {
                Os.LINUX -> OperatingSystemFamily.LINUX
                Os.WINDOWS -> OperatingSystemFamily.WINDOWS
                Os.MAC -> OperatingSystemFamily.MACOS
            }

        fun architectureOf(platform: Platform) =
            when (platform.arch) {
                Arch.X64 -> MachineArchitecture.X86_64
                Arch.AARCH64 -> MachineArchitecture.ARM64
            }
    }
}
