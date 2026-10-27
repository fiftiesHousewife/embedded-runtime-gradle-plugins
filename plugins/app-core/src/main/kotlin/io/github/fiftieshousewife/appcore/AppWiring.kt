package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.CheckRuntimeSupport
import io.github.fiftieshousewife.appcore.tasks.InstallAiSkills
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File

/** The tasks registered before the targets are known, wired to them afterwards. */
private data class Lifecycle(
    val allArchives: TaskProvider<Task>,
    val hostTasks: HostTasks,
    val dockerContext: DockerContext,
    val publishing: AppPublishing,
)

/**
 * The pipeline the Java and Node plugins share; not a public API (AGENTS.md draws it). Targets are
 * set in the build script, so per-target tasks are registered once it has run; the tasks a build
 * script may name are registered up front and wired to their target then.
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

    val gitCommit: Provider<String> = extension.gitCommit

    /** The commit's time, the image's `created` label, so rebuilding a commit gives the same image. */
    val gitCommitTime: Provider<String> = Git(project).output("log", "-1", "--format=%cI")

    private val rpmPackages by lazy { RpmPackages(project, this) }

    val runtimePolicy: FileCollection =
        RuntimePolicySource.files(
            project,
            providers.gradleProperty(ecosystem.property("securityPolicy")).orElse(extension.securityPolicy),
            providers.gradleProperty(
                ecosystem.property("securityPolicyArtifact"),
            ).orElse(extension.securityPolicyArtifact),
        )

    fun applyConventions() {
        AppSettings.requireApplied(project, ecosystem.settingsPluginId)
        extension.appName.convention(project.name)
        extension.targets.convention(
            providers.gradleProperty(ecosystem.property("targets")).map(Target::parseList).orElse(listOf(host)),
        )
        val projectDir = layout.projectDirectory
        // The project's root Dockerfile; one an older project keeps in src/deploy/docker/ is still used.
        val legacy = projectDir.file("src/deploy/docker/Dockerfile")
        extension.dockerfile.convention(
            layout.file(
                providers.provider {
                    legacy.asFile.takeIf { it.isFile && !projectDir.file("Dockerfile").asFile.isFile }
                        ?: projectDir.file("Dockerfile").asFile
                },
            ),
        )
        ExtensionConventions(project, ecosystem).apply(extension)
        // Unset, each target gets the format native to it: ArchiveFormat.nativeTo.
        extension.archiveFormat.convention(setting("archiveFormat", ArchiveFormat::parse))
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

    private fun <T : Any> setting(name: String, parse: (String) -> T): Provider<T> =
        providers.gradleProperty(ecosystem.property(name)).map(parse)

    /** A resolvable configuration holding exactly one runtime archive. */
    fun runtimeArchive(
        name: String,
        artifact: Provider<RuntimeArtifact>,
    ): NamedDomainObjectProvider<ResolvableConfiguration> = project.configurations.resolvable(name) {
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

    fun singleFile(configuration: NamedDomainObjectProvider<ResolvableConfiguration>): Provider<File> =
        configuration.flatMap { it.elements }.map { it.single().asFile }

    fun registerRuntimeSupportCheck(runtime: Provider<EmbeddedRuntime>) =
        CheckRuntimeSupport.register(project, runtime, extension.runtimeSupport)

    /** Registers the pipeline; [contribute] registers each target's ecosystem tasks once targets are known. */
    fun register(contribute: (Target) -> EcosystemSpec) {
        project.pluginManager.apply("distribution")
        val lifecycle = registerLifecycle()
        project.afterEvaluate { registerTargets(lifecycle, contribute) }
    }

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
            description =
                "Writes the plugins' skills and hooks for Copilot and Devin into .agents/, .github/ and .devin/"
            projectDirectory.set(layout.projectDirectory)
        }
        val publishing = AppPublishing(project, this, services.components).apply { register(smoke) }
        tasks.named("assemble") { dependsOn(allArchives) }
        tasks.named("check") { dependsOn(smoke) }
        // Every archive feeding the app, its jar included, must be byte-reproducible.
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
        lifecycle.dockerContext.useLinuxArchives(built)
        lifecycle.publishing.addTargets(built, untestedTargets(targets, host, extension.smokeTestEnabled.get()))
        rpmPackages.register(built, listOf(lifecycle.publishing.guard, lifecycle.hostTasks.smoke))
    }

    companion object {
        const val VERIFICATION_GROUP = "verification"
        const val APPLICATION_GROUP = "application"
    }
}
