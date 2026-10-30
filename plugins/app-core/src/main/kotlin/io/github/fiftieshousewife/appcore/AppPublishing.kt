package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.VerifyPublishable
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.ConsumableConfiguration
import org.gradle.api.artifacts.DependencyScopeConfiguration
import org.gradle.api.artifacts.ExternalModuleDependency
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
 * Publishes two modules at the app's version: `<app>`, every target's app archive and SBOM, and
 * `<app>-runtime`, every target's runtime archive. Each is a Maven publication of a real software
 * component with one variant per target; with them come the corporate repository when one is
 * configured, opt-in signing, and the guard that refuses to publish an archive nobody has started.
 *
 * The runtime is a module of its own so that a release whose runtime is unchanged publishes the same
 * runtime bytes again, which a repository that stores by checksum keeps once. Each app variant depends
 * on the runtime module, so a Gradle build resolving the app gets the runtime for the same machine.
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
    private val runtimePublicationName = "${publicationName}Runtime"
    private val component: AdhocComponentWithVariants = components.adhoc(publicationName)
    private val runtimeComponent: AdhocComponentWithVariants = components.adhoc(runtimePublicationName)
    private val publishing: PublishingExtension

    /** Fails a publication of archives nobody started. */
    private val guard: TaskProvider<VerifyPublishable> =
        project.tasks.register("appVerifyPublishable", VerifyPublishable::class.java) {
            description = "Fails publication of archives that were not smoke-tested on this machine"
            allowUntested.set(wiring.facts.allowUntestedPublish)
            allowUntestedProperty.set(ecosystem.property("allowUntestedPublish"))
        }

    init {
        project.pluginManager.apply("maven-publish")
        publishing = project.extensions.getByType(PublishingExtension::class.java)
    }

    /** The binary repository's releases or snapshots repository, with its login. */
    private fun publishTo(binaryRepo: BinaryRepository) {
        publishing.repositories.maven {
            name = BinaryRepository.CREDENTIALS_NAME
            url = binaryRepo.publishUrl(project.version.toString().endsWith("-SNAPSHOT"))
            isAllowInsecureProtocol = binaryRepo.allowInsecureProtocol
            if (binaryRepo.borrowedFrom == null) {
                credentials(PasswordCredentials::class.java)
            } else {
                AppSettings.authenticateExplicitly(this, binaryRepo.login(project.providers).orNull)
            }
        }
    }

    fun register(smoke: TaskProvider<AppSmokeTest>) {
        val app = extension.appName.get()
        publication(publicationName, app, component, "$app: launchers, libraries and application, without the runtime")
        publication(runtimePublicationName, app + RuntimeArchive.SUFFIX, runtimeComponent, "The runtime $app runs on")

        // The publish URL depends on whether the version is a snapshot, which build scripts set
        // after plugins are applied; this is the one place an afterEvaluate is warranted.
        project.afterEvaluate { wiring.binaryRepo?.let(::publishTo) }

        project.providers.gradleProperty("signingKey").orNull?.let { key ->
            project.pluginManager.apply("signing")
            project.extensions.getByType(SigningExtension::class.java).apply {
                useInMemoryPgpKeys(key, project.providers.gradleProperty("signingPassword").orNull)
                sign(publishing.publications.getByName(publicationName))
                sign(publishing.publications.getByName(runtimePublicationName))
            }
        }

        project.tasks.withType(PublishToMavenRepository::class.java).configureEach { dependsOn(guard, smoke) }
        project.tasks.withType(PublishToMavenLocal::class.java).configureEach { dependsOn(smoke) }
    }

    private fun publication(name: String, id: String, from: AdhocComponentWithVariants, summary: String) =
        publishing.publications.register(name, MavenPublication::class.java) {
            artifactId = id
            from(from)
            pom {
                // Every artifact carries a target classifier, so there is no main artifact.
                packaging = "pom"
                this.name.set(id)
                description.set(summary)
            }
        }

    /** Adds each target's archives as variants and its SBOM as an artifact; [untested] were never started here. */
    fun addTargets(built: List<TargetTasks>, untested: List<Target>) {
        guard.configure { untestedTargets.set(untested.map { it.id }) }
        val runtime = runtimeDependency()
        built.forEach { addVariant(it, runtime) }
        publishing.publications.named(publicationName, MavenPublication::class.java) {
            built.forEach { t ->
                artifact(t.sbom) {
                    classifier = "${t.target.id}-cyclonedx"
                    extension = "json"
                }
            }
        }
    }

    /** The runtime module at this version: in the Gradle Module Metadata, and optional in the POM. */
    private fun runtimeDependency() = project.configurations.dependencyScope("${publicationName}RuntimeModule") {
        description = "The runtime module each app variant depends on"
        val coordinates = extension.appName.map { "${project.group}:$it${RuntimeArchive.SUFFIX}:${project.version}" }
        dependencies.addLater(coordinates.map(project.dependencies::create).map { it as ExternalModuleDependency })
    }

    private fun addVariant(built: TargetTasks, runtime: NamedDomainObjectProvider<DependencyScopeConfiguration>) {
        val app = elements(built, runtime = false) {
            extendsFrom(runtime.get())
            outgoing.artifact(built.archive)
        }
        // Optional in the POM: a Maven build resolving the app must not go looking for a runtime jar.
        component.addVariantsFromConfiguration(app.get()) {
            mapToMavenScope(MAVEN_SCOPE)
            mapToOptional()
        }
        val ownRuntime = elements(built, runtime = true) {
            outgoing.artifact(built.runtimeArchive)
        }
        runtimeComponent.addVariantsFromConfiguration(ownRuntime.get()) { mapToMavenScope(MAVEN_SCOPE) }
    }

    /** One target's variant of the app module, or of the runtime module when [runtime]. */
    private fun elements(
        built: TargetTasks,
        runtime: Boolean,
        configure: ConsumableConfiguration.() -> Unit,
    ): NamedDomainObjectProvider<ConsumableConfiguration> {
        val objects = project.objects
        val kind = if (runtime) "Runtime" else ""
        val category = if (runtime) "runtime" else "app"
        return project.configurations.consumable("$publicationName${kind}Elements${built.target.taskSuffix}") {
            description = "The $category archive for ${built.target.id}"
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "${ecosystem.id}-$category"))
                // The runtime and every dependency are inside the archives.
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
            configure()
        }
    }

    companion object {
        private const val MAVEN_SCOPE = "runtime"

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
