package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import io.github.fiftieshousewife.appcore.tasks.VerifyPublishable
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.credentials.PasswordCredentials
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
 * Publishes the archive: a Maven publication of a real software component plus its SBOM, the
 * corporate repository when one is configured, opt-in signing, and the guard that refuses to
 * publish an archive nobody has started.
 */
internal class AppPublishing(
    private val project: Project,
    private val wiring: AppWiring,
    private val components: SoftwareComponentFactory,
) {
    private val extension = wiring.extension
    private val ecosystem = wiring.ecosystem
    private val target = wiring.target

    /** The publication, its software component and the consumable configuration behind it. */
    private val publicationName = ecosystem.extensionName

    fun register(
        tar: TaskProvider<Tar>,
        sbom: TaskProvider<GenerateSbom>,
        smoke: TaskProvider<AppSmokeTest>,
    ) {
        project.pluginManager.apply("maven-publish")
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val targetId = target.map { it.id }

        publishing.publications.register(publicationName, MavenPublication::class.java) {
            artifactId = extension.appName.get()
            from(appComponent(tar))
            artifact(sbom.flatMap { it.outputFile }) {
                classifier = "${targetId.get()}-cyclonedx"
                extension = "json"
                builtBy(sbom)
            }
            pom {
                // Every artifact carries a target classifier, so there is no main artifact.
                packaging = "pom"
                name.set(extension.appName)
                description.set("Self-contained ${extension.appName.get()} archive (runtime, libraries, application)")
            }
        }

        // The publish URL depends on whether the version is a snapshot, which build scripts set
        // after plugins are applied; this is the one place an afterEvaluate is warranted.
        project.afterEvaluate {
            val artifactory = wiring.artifactory ?: return@afterEvaluate
            publishing.repositories.maven {
                name = Artifactory.CREDENTIALS_NAME
                url = artifactory.publishUrl(project.version.toString().endsWith("-SNAPSHOT"))
                isAllowInsecureProtocol = artifactory.allowInsecureProtocol
                credentials(PasswordCredentials::class.java)
            }
        }

        project.providers.gradleProperty("signingKey").orNull?.let { key ->
            project.pluginManager.apply("signing")
            project.extensions.getByType(SigningExtension::class.java).apply {
                useInMemoryPgpKeys(key, project.providers.gradleProperty("signingPassword").orNull)
                sign(publishing.publications.getByName(publicationName))
            }
        }

        val guard =
            project.tasks.register("appVerifyPublishable", VerifyPublishable::class.java) {
                group = AppWiring.VERIFICATION_GROUP
                description = "Fails publication of archives that were not smoke-tested on this machine"
                crossBuild.set(wiring.cross)
                smokeTestEnabled.set(extension.smokeTestEnabled)
                allowUntested.set(extension.allowUntestedPublish)
                allowUntestedProperty.set(ecosystem.property("allowUntestedPublish"))
                this.target.set(targetId)
            }
        project.tasks.withType(PublishToMavenRepository::class.java).configureEach { dependsOn(guard, smoke) }
        project.tasks.withType(PublishToMavenLocal::class.java).configureEach { dependsOn(smoke) }
    }

    /**
     * A real software component rather than artifacts bolted onto a bare publication: it carries
     * the target's OS and architecture as Gradle's own standard attributes, so the published Gradle
     * Module Metadata describes which machine the archive is for and a consuming Gradle build can
     * select it instead of naming a classifier by hand. Maven-only consumers are unaffected — the
     * classifier is still there.
     *
     * One build produces one target, so each target build publishes its own module metadata.
     * Publishing several targets to one coordinate therefore needs either distinct artifactIds or
     * module metadata turned off; see the README.
     */
    private fun appComponent(tar: TaskProvider<Tar>): SoftwareComponent {
        val objects = project.objects
        val elements =
            project.configurations.consumable("${publicationName}Elements") {
                description = "The app archive for one target"
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "${ecosystem.id}-app"))
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
        return components.adhoc(publicationName).apply {
            addVariantsFromConfiguration(elements.get()) { mapToMavenScope("runtime") }
        }
    }

    private companion object {
        fun osFamilyOf(target: Target) =
            when (target.os) {
                Os.LINUX -> OperatingSystemFamily.LINUX
                Os.WINDOWS -> OperatingSystemFamily.WINDOWS
                Os.MAC -> OperatingSystemFamily.MACOS
            }

        fun architectureOf(target: Target) =
            when (target.arch) {
                Arch.X64 -> MachineArchitecture.X86_64
                Arch.AARCH64 -> MachineArchitecture.ARM64
            }
    }
}
