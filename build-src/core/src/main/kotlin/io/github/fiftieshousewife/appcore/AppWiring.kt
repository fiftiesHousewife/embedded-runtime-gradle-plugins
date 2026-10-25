package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.GenerateAppManifest
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import io.github.fiftieshousewife.appcore.tasks.GitCommitValueSource
import io.github.fiftieshousewife.appcore.tasks.RunApp
import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.CopySpec
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import java.io.File
import java.time.Duration

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

/** What an ecosystem plugin contributes to the shared archive pipeline for one target. */
class EcosystemSpec(
    val runtimeName: String,
    val runtimeVersion: Provider<String>,
    val posixExec: Provider<String>,
    val windowsExec: Provider<String>,
    /** Adds the ecosystem's own content (`runtime/`, plus `lib/` or `app/`) to the assembled directory. */
    val contents: Sync.() -> Unit,
    /** Feeds library components into the SBOM task. */
    val sbom: GenerateSbom.() -> Unit,
)

/** The tasks built for one target. */
class TargetTasks(
    val target: Target,
    val assemble: TaskProvider<Sync>,
    val tar: TaskProvider<Tar>,
    val sbom: TaskProvider<GenerateSbom>,
)

/**
 * The pipeline the Java and Node plugins share; not a public API. Once per target:
 *
 * ```
 * appLaunchers<T> ─┐
 * appManifest<T>  ─┼─► appAssemble<T> ─► appTar<T> ──► publish
 * appSbom<T>      ─┘
 * ```
 *
 * plus, for this machine's archive only, `appSmokeTest` and `runApp`, and for a Linux archive
 * `appDockerContext`. `appTar` builds every target's archive.
 *
 * Targets are set in the build script, so per-target tasks are registered once it has run. The
 * tasks a build script may refer to by name are registered up front and wired to their target then.
 *
 * Every archive, whatever the target, is a `.tar.gz` with one top-level directory
 * `<app>-<version>/`. Windows 10+ extracts it with the built-in `tar.exe`.
 */
class AppWiring(
    private val project: Project,
    val ecosystem: Ecosystem,
    val extension: AppExtension,
    private val archives: ArchiveOperations,
    private val components: SoftwareComponentFactory,
) {
    private val providers = project.providers
    private val layout = project.layout
    private val tasks = project.tasks

    val artifactory: Artifactory? = Artifactory.from(providers)
    val host: Target = Target.host()
    val workDir: Provider<Directory> = layout.buildDirectory.dir("app-work")
    val taskGroup: String get() = ecosystem.taskGroup

    private val appName: Provider<String> get() = extension.appName

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
        extension.smokeTestEnabled.convention(true)
        extension.smokeTestTimeout.convention(DEFAULT_SMOKE_TEST_TIMEOUT)
        extension.allowUntestedPublish.convention(
            providers.gradleProperty(ecosystem.property("allowUntestedPublish")).map(String::toBoolean).orElse(false),
        )
    }

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
    fun extractRuntime(
        name: String,
        archive: Provider<File>,
        destination: Provider<Directory>,
    ): TaskProvider<Sync> =
        tasks.register(name, Sync::class.java) {
            group = taskGroup
            description = "Extracts a runtime archive"
            // Lambdas stored in tasks must not capture this class: it holds the Project, which the
            // configuration cache cannot serialise. Hence the local copies here and below.
            val archiveOps = archives
            from(archive.map { file -> if (file.name.endsWith(".zip")) archiveOps.zipTree(file) else archiveOps.tarTree(file) })
            into(destination)
            dropTopLevelDirectory(this)
        }

    /** The single file a [runtimeArchive] configuration resolves to. */
    fun singleFile(configuration: NamedDomainObjectProvider<ResolvableConfiguration>): Provider<File> =
        configuration.flatMap { it.elements }.map { it.single().asFile }

    /**
     * Registers the whole pipeline. [contribute] is called once per target, after the build script
     * has run, to register that target's ecosystem-specific tasks.
     */
    fun register(contribute: (Target) -> EcosystemSpec) {
        val allTars =
            tasks.register("appTar") {
                group = taskGroup
                description = "Builds the archive for every target"
            }
        val smoke = registerSmokeTest()
        val runApp = registerRunApp()
        val dockerContext = DockerContext(project, this, archives)
        val publishing = AppPublishing(project, this, components)
        publishing.register(smoke)

        tasks.named("assemble") { dependsOn(allTars) }
        tasks.named("check") { dependsOn(smoke) }

        // Every archive feeding the app (the application jar included) must be byte-reproducible,
        // so rebuilding a commit yields an identical archive checksum. Gradle 9 does this by default.
        tasks.withType(AbstractArchiveTask::class.java).configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }

        project.afterEvaluate {
            extension.targets.finalizeValue()
            val targets = extension.targets.get()
            if (targets.isEmpty()) throw GradleException("${ecosystem.extensionName}.targets is empty; name at least one target")
            val pipeline = TargetPipeline(project, this@AppWiring)
            val built = targets.map { pipeline.register(it, contribute(it)) }
            allTars.configure { dependsOn(built.map { it.tar }) }

            val hostTasks = built.firstOrNull { it.target == host }
            if (hostTasks != null) {
                smoke.configure { archiveFiles.from(hostTasks.tar.flatMap { it.archiveFile }) }
                runApp.configure {
                    dependsOn(hostTasks.assemble)
                    appDir.set(layout.dir(hostTasks.assemble.map { it.destinationDir }))
                }
            }
            dockerContext.useLinuxArchive(built)
            publishing.addTargets(built, untested = targets.filter { it != host || !extension.smokeTestEnabled.get() })
        }
    }

    private fun registerSmokeTest() =
        tasks.register("appSmokeTest", AppSmokeTest::class.java) {
            group = VERIFICATION_GROUP
            description = "Extracts this machine's archive, starts it and waits for its healthcheck"
            healthTimeout.set(extension.smokeTestTimeout)
            resultFile.set(workDir.map { it.file("smoke-test.txt") })
            val enabled = extension.smokeTestEnabled
            onlyIf("${this@AppWiring.ecosystem.extensionName}.smokeTestEnabled is true") { enabled.get() }
        }

    private fun registerRunApp() =
        tasks.register("runApp", RunApp::class.java) {
            group = APPLICATION_GROUP
            description = "Runs this machine's assembled app in the foreground (bin/<app> run)"
            appName.set(this@AppWiring.appName)
            windows.set(host.isWindows)
            args.convention(
                providers.gradleProperty("args").map { it.split(" ").filter(String::isNotBlank) }.orElse(emptyList()),
            )
            val dir = appDir
            onlyIf("an archive is built for this machine") { dir.isPresent }
        }

    companion object {
        const val VERIFICATION_GROUP = "verification"
        const val APPLICATION_GROUP = "application"

        private val DEFAULT_SMOKE_TEST_TIMEOUT: Duration = Duration.ofSeconds(60)

        /**
         * Exec bits are set explicitly rather than trusted from the filesystem: a Windows checkout
         * or a jlink run on Windows has none, and a missing bit on `jspawnhelper` breaks
         * `ProcessBuilder` on Linux at runtime, long after the build succeeded.
         */
        private val EXECUTABLES =
            listOf("bin/**", "runtime/bin/**", "runtime/lib/jspawnhelper", "runtime/lib/jexec")
                .let { it + it.map { pattern -> "*/$pattern" } } // also inside the archive's top-level directory

        fun executableBits(spec: CopySpec) {
            spec.filesMatching(EXECUTABLES) { permissions { unix("rwxr-xr-x") } }
        }

        /**
         * Strips an archive's single top-level directory (`node-v24.../`, `<app>-<version>/`) while
         * copying, or renames it to [replacement].
         */
        fun dropTopLevelDirectory(
            spec: CopySpec,
            replacement: String? = null,
        ) {
            spec.eachFile {
                val segments = listOfNotNull(replacement) + relativePath.segments.drop(1)
                relativePath = RelativePath.parse(!isDirectory, segments.joinToString("/"))
            }
            spec.includeEmptyDirs = false
        }
    }
}
