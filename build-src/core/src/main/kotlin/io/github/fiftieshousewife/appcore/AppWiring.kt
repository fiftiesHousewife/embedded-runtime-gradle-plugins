package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateAppManifest
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import io.github.fiftieshousewife.appcore.tasks.GitCommitValueSource
import io.github.fiftieshousewife.appcore.tasks.InstallAiSkills
import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.CopySpec
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File

/** The names that tell the Java and Node plugins apart wherever they share code. */
data class Ecosystem(
    /** `java` or `node`, recorded in `META-INF/app.json`. */
    val id: String,
    /** The DSL block, `javaApp` or `nodeApp`, which also prefixes this plugin's Gradle properties. */
    val extensionName: String,
    val settingsPluginId: String,
) {
    /** The task group every task this plugin registers belongs to, e.g. `java app`. */
    val taskGroup: String get() = "$id app"

    fun property(name: String) = "$extensionName.$name"
}

/** The Gradle services a plugin is injected with and the shared pipeline needs. */
data class AppServices(val archives: ArchiveOperations, val components: SoftwareComponentFactory)

/** Where an ecosystem's launchers come from. */
sealed interface Launchers {
    /** Rendered by [GenerateLaunchers] from these start lines (Node). */
    data class Generated(val posixExec: Provider<String>, val windowsExec: Provider<String>) : Launchers

    /** Gradle's own `startScripts` task, which writes both scripts; each target takes its own (Java). */
    data class StartScripts(val task: TaskProvider<out Task>) : Launchers
}

/** What an ecosystem plugin contributes to the shared archive pipeline for one target. */
data class EcosystemSpec(
    val runtimeName: String,
    val runtimeVersion: Provider<String>,
    /** Where `bin/` comes from for a target. */
    val launchers: Launchers,
    /** Adds the ecosystem's own content (`runtime/`, plus `lib/` or `app/`) to the assembled directory. */
    val contents: CopySpec.() -> Unit,
    /** Feeds library components into the SBOM task. */
    val sbom: GenerateSbom.() -> Unit,
)

/** The tasks registered before the targets are known, wired to them afterwards. */
private data class Lifecycle(
    val allArchives: TaskProvider<Task>,
    val hostTasks: HostTasks,
    val dockerContext: DockerContext,
    val publishing: AppPublishing,
)

/**
 * The targets whose archives are never started here: every target but this machine's, and this
 * machine's too when the smoke test is turned off.
 */
internal fun untestedTargets(targets: Collection<Target>, host: Target, smokeTestEnabled: Boolean): List<Target> =
    targets.filter { it != host || !smokeTestEnabled }

/** The tasks built for one target. */
class TargetTasks(
    val target: Target,
    val assemble: TaskProvider<Sync>,
    /** `appArchive<T>`: a Tar or Zip task, as [AppExtension.archiveFormat] says. */
    val archive: TaskProvider<out AbstractArchiveTask>,
    val sbom: TaskProvider<GenerateSbom>,
)

/**
 * The pipeline the Java and Node plugins share; not a public API. Once per target:
 *
 * ```
 * startScripts / appLaunchers<T> ─┐
 * appManifest<T>                  ─┼─► distribution <target> ─► <target>DistTar/Zip ──► publish
 * appSbom<T>                      ─┘                          ─► install<Target>Dist
 * ```
 *
 * plus, for this machine's archive only, `appSmokeTest` and `runApp`, and for a Linux archive
 * `appDockerContext`, and for a Linux target with `rpm { enabled = true }` `appRpm<T>`. `appArchive`
 * builds every target's archive.
 *
 * Targets are set in the build script, so per-target tasks are registered once it has run. The
 * tasks a build script may refer to by name are registered up front and wired to their target then.
 *
 * Every archive, whatever the target, is a `.tar.gz` (or the [ArchiveFormat] chosen) with one
 * top-level directory `<app>-<version>/`. Windows 10+ extracts a `.tar.gz` with the built-in `tar.exe`.
 */
class AppWiring(
    private val project: Project,
    val ecosystem: Ecosystem,
    val extension: AppExtension,
    private val services: AppServices,
) {
    private val archives = services.archives

    private val providers = project.providers
    private val layout = project.layout
    private val tasks = project.tasks

    val binaryRepo: BinaryRepository? = BinaryRepository.from(providers)
    val host: Target = Target.host()
    val workDir: Provider<Directory> = layout.buildDirectory.dir("app-work")
    val taskGroup: String get() = ecosystem.taskGroup
    val version: Provider<String> = providers.provider { project.version.toString() }

    /** `-P<ecosystem>.gitCommit`, else `git rev-parse HEAD`, else `unknown` outside a checkout. */
    val gitCommit: Provider<String> =
        providers
            .gradleProperty(ecosystem.property("gitCommit"))
            .orElse(
                providers.of(GitCommitValueSource::class.java) {
                    parameters.workingDirectory.set(layout.projectDirectory)
                },
            )
            .orElse("unknown")

    private val appName: Provider<String> get() = extension.appName

    private val rpmPackages by lazy { RpmPackages(project, this) }

    /** The organisation's runtime policy, if one is configured; see [RuntimePolicySource]. */
    val runtimePolicy: FileCollection = RuntimePolicySource.files(project, ecosystem.extensionName)

    fun applyConventions() {
        AppSettings.requireApplied(project, ecosystem.settingsPluginId)
        extension.appName.convention(project.name)
        extension.targets.convention(
            providers.gradleProperty(ecosystem.property("targets")).map(Target::parseList).orElse(listOf(host)),
        )
        val projectDir = layout.projectDirectory
        extension.dockerfile.convention(
            layout.file(
                providers.provider {
                    listOf(projectDir.file("Dockerfile"), projectDir.file("src/deploy/docker/Dockerfile"))
                        .map { it.asFile }
                        .firstOrNull(File::isFile)
                },
            ),
        )
        extension.configDefaults.convention(projectDir.dir("src/deploy/conf"))
        extension.archiveFormat.convention(setting("archiveFormat", ArchiveFormat::parse).orElse(ArchiveFormat.TAR_GZ))
        extension.runtimeOptions.convention(
            setting("runtimeOptions", RuntimeOptions::parse).orElse(RuntimeOptions.OPEN),
        )
        rpmPackages.applyConventions()
        extension.smokeTestEnabled.convention(true)
        extension.smokeTestTimeout.convention(HostTasks.DEFAULT_SMOKE_TEST_TIMEOUT)
        extension.allowUntestedPublish.convention(
            providers.gradleProperty(ecosystem.property("allowUntestedPublish")).map(String::toBoolean).orElse(false),
        )
    }

    /** `-P<ecosystem>.<name>`, parsed. */
    private fun <T : Any> setting(name: String, parse: (String) -> T): Provider<T> =
        providers.gradleProperty(ecosystem.property(name)).map(parse)

    /** A resolvable configuration holding exactly one runtime archive. */
    fun runtimeArchive(
        name: String,
        artifact: Provider<RuntimeArtifact>,
    ): NamedDomainObjectProvider<ResolvableConfiguration> =
        // `resolvable` states the role up front, rather than flipping the canBeConsumed /
        // canBeResolved flags on a general-purpose configuration after the fact.
        project.configurations.resolvable(name) {
            isTransitive = false
            description = "Runtime archive resolved from the runtime repository"
            dependencies.addLater(
                artifact.map { a ->
                    (project.dependencies.create(a.notation) as ExternalModuleDependency).apply {
                        isTransitive = false
                        artifact {
                            this.name = a.artifact
                            this.type = a.extension
                            this.extension = a.extension
                            this.classifier = a.classifier
                        }
                    }
                },
            )
        }

    /** Extracts the single runtime archive in [archive], dropping its top-level directory. */
    fun extractRuntime(name: String, archive: Provider<File>, destination: Provider<Directory>): TaskProvider<Sync> =
        tasks.register(name, Sync::class.java) {
            group = taskGroup
            description = "Extracts a runtime archive"
            // Lambdas stored in tasks must not capture this class: it holds the Project, which the
            // configuration cache cannot serialise. Hence the local copies here and below.
            val archiveOps = archives
            from(archive.map { archiveOps.archiveTree(it) })
            into(destination)
            AppCopySpecs.dropTopLevelDirectory(this)
            // JDKs ship read-only files (legal/), which an in-place sync cannot overwrite when the
            // runtime is extracted again: start empty, and keep the copies writable by their owner.
            doFirst { destinationDir.deleteRecursively() }
            AppCopySpecs.ownerWritable(this)
        }

    /** The single file a [runtimeArchive] configuration resolves to. */
    fun singleFile(configuration: NamedDomainObjectProvider<ResolvableConfiguration>): Provider<File> =
        configuration.flatMap { it.elements }.map { it.single().asFile }

    /**
     * Registers the whole pipeline. [contribute] is called once per target, after the build script
     * has run, to register that target's ecosystem-specific tasks.
     */
    fun register(contribute: (Target) -> EcosystemSpec) {
        // Each target becomes a distribution of its own (TargetPipeline).
        project.pluginManager.apply("distribution")
        val lifecycle = registerLifecycle()
        project.afterEvaluate { registerTargets(lifecycle, contribute) }
    }

    /** The tasks a build script may name, registered before the targets are known. */
    private fun registerLifecycle(): Lifecycle {
        val allArchives =
            tasks.register("appArchive") {
                group = taskGroup
                description = "Builds the archive for every target"
            }
        val hostTasks = HostTasks(project, this)
        val smoke = hostTasks.smoke
        tasks.register("aiSkills", InstallAiSkills::class.java) {
            group = "help"
            description = "Writes the plugins' skills and prompt files for Copilot and Devin into .agents/ and .github/"
            projectDirectory.set(layout.projectDirectory)
        }
        val publishing = AppPublishing(project, this, services.components).apply { register(smoke) }
        tasks.named("assemble") { dependsOn(allArchives) }
        tasks.named("check") { dependsOn(smoke) }
        // Every archive feeding the app (the application jar included) must be byte-reproducible,
        // so rebuilding a commit yields an identical archive checksum. Gradle 9 does this by default.
        tasks.withType(AbstractArchiveTask::class.java).configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }
        return Lifecycle(allArchives, hostTasks, DockerContext(project, this, services.archives), publishing)
    }

    private fun registerTargets(lifecycle: Lifecycle, contribute: (Target) -> EcosystemSpec) {
        extension.targets.finalizeValue()
        extension.archiveFormat.finalizeValue()
        val targets = extension.targets.get()
        if (targets.isEmpty()) throw GradleException("${ecosystem.extensionName}.targets is empty; name at least one")
        val pipeline = TargetPipeline(project, this)
        val built = targets.map { pipeline.register(it, contribute(it)) }
        lifecycle.allArchives.configure { dependsOn(built.map { it.archive }) }
        built.firstOrNull { it.target == host }?.let(lifecycle.hostTasks::use)
        lifecycle.dockerContext.useLinuxArchive(built)
        lifecycle.publishing.addTargets(built, untestedTargets(targets, host, extension.smokeTestEnabled.get()))
        rpmPackages.register(built, listOf(lifecycle.publishing.guard, lifecycle.hostTasks.smoke))
    }

    companion object {
        const val VERIFICATION_GROUP = "verification"
        const val APPLICATION_GROUP = "application"
    }
}
