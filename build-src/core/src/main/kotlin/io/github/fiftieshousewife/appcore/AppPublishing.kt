package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.VerifyPublishable
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.TaskProvider
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.plugins.signing.SigningExtension

/**
 * Publishes every target's archive and SBOM under one set of coordinates: a Maven publication of a
 * real software component with one variant per target, the corporate repository when one is
 * configured, opt-in signing, and the guard that refuses to publish an archive nobody has started.
 *
 * Each variant carries its target's OS and architecture as Gradle's standard attributes, so the
 * Gradle Module Metadata describes which archive is for which machine and a consuming Gradle build
 * can select one instead of naming a classifier. Maven-only consumers use the classifier.
 */
internal class AppPublishing(
    private val project: Project,
    private val wiring: AppWiring,
    components: SoftwareComponentFactory,
) {
    private val extension = wiring.extension
    private val ecosystem = wiring.ecosystem

    private val publicationName = ecosystem.extensionName
    private val component: AdhocComponentWithVariants = components.adhoc(publicationName)
    private val publishing: PublishingExtension

    /** Fails a publication of archives nobody started; RPM uploads depend on it too. */
    val guard: TaskProvider<VerifyPublishable> =
        project.tasks.register("appVerifyPublishable", VerifyPublishable::class.java) {
            group = AppWiring.VERIFICATION_GROUP
            description = "Fails publication of archives that were not smoke-tested on this machine"
            allowUntested.set(extension.allowUntestedPublish)
            allowUntestedProperty.set(ecosystem.property("allowUntestedPublish"))
        }

    init {
        project.pluginManager.apply("maven-publish")
        publishing = project.extensions.getByType(PublishingExtension::class.java)
    }

    fun register(smoke: TaskProvider<AppSmokeTest>) {
        publishing.publications.register(publicationName, MavenPublication::class.java) {
            artifactId = extension.appName.get()
            from(component)
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
            val binaryRepo = wiring.binaryRepo ?: return@afterEvaluate
            publishing.repositories.maven {
                name = BinaryRepository.CREDENTIALS_NAME
                url = binaryRepo.publishUrl(project.version.toString().endsWith("-SNAPSHOT"))
                isAllowInsecureProtocol = binaryRepo.allowInsecureProtocol
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

        project.tasks.withType(PublishToMavenRepository::class.java).configureEach { dependsOn(guard, smoke) }
        project.tasks.withType(PublishToMavenLocal::class.java).configureEach { dependsOn(smoke) }
    }

    /** Adds each target's archive as a variant and its SBOM as an artifact; [untested] were never started here. */
    fun addTargets(built: List<TargetTasks>, untested: List<Target>) {
        guard.configure { untestedTargets.set(untested.map { it.id }) }
        built.forEach(::addVariant)
        publishing.publications.named(publicationName, MavenPublication::class.java) {
            built.forEach { t ->
                artifact(t.sbom) {
                    classifier = "${t.target.id}-cyclonedx"
                    extension = "json"
                }
            }
        }
    }

    private fun addVariant(built: TargetTasks) {
        val objects = project.objects
        val elements =
            project.configurations.consumable("${publicationName}Elements${built.target.taskSuffix}") {
                description = "The app archive for ${built.target.id}"
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "${ecosystem.id}-app"))
                    // The runtime and every dependency are inside the archive.
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EMBEDDED))
                    attribute(
                        OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                        objects.named(OperatingSystemFamily::class.java, osFamilyOf(built.target)),
                    )
                    attribute(
                        MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                        objects.named(MachineArchitecture::class.java, architectureOf(built.target)),
                    )
                }
                outgoing.artifact(built.archive)
            }
        component.addVariantsFromConfiguration(elements.get()) { mapToMavenScope("runtime") }
    }

    companion object {
        fun osFamilyOf(target: Target) = when (target.os) {
            Os.LINUX -> OperatingSystemFamily.LINUX
            Os.WINDOWS -> OperatingSystemFamily.WINDOWS
            Os.MAC -> OperatingSystemFamily.MACOS
        }

        fun architectureOf(target: Target) = when (target.arch) {
            Arch.X64 -> MachineArchitecture.X86_64
            Arch.AARCH64 -> MachineArchitecture.ARM64
        }
    }
}
