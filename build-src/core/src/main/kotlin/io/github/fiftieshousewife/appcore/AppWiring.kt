package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.GenerateAppManifest
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import io.github.fiftieshousewife.appcore.tasks.GitCommitValueSource
import io.github.fiftieshousewife.appcore.tasks.RunApp
import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
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

/** What an ecosystem plugin contributes to the shared archive pipeline. */
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

/**
 * The pipeline the Java and Node plugins share; not a public API.
 *
 * ```
 * appLaunchers ─┐
 * appManifest  ─┼─► appAssemble ─► appTar ─► appSmokeTest ─► publish
 * appSbom      ─┘                     └────► appDockerContext
 * ```
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
    val target: Provider<Target> get() = extension.target
    val cross: Provider<Boolean> get() = target.map { it != host }
    val windowsTarget: Provider<Boolean> get() = target.map { it.isWindows }
    val workDir: Provider<Directory> = layout.buildDirectory.dir("app-work")
    val taskGroup: String get() = ecosystem.taskGroup

    private val appName: Provider<String> get() = extension.appName
    private val versionString: Provider<String> = providers.provider { project.version.toString() }
    private val groupString: Provider<String> = providers.provider { project.group.toString() }
    private val targetId: Provider<String> get() = target.map { it.id }

    /** `<app>-<version>`: the archive's single top-level directory. */
    private val topDirectory: Provider<String> get() = appName.zip(versionString) { n, v -> "$n-$v" }

    fun applyConventions() {
        AppSettings.requireApplied(project, ecosystem.settingsPluginId)
        extension.appName.convention(project.name)
        extension.target.convention(
            providers.gradleProperty(ecosystem.property("target")).map(Target::parse).orElse(host),
        )
        extension.configDefaults.convention(layout.projectDirectory.dir("src/deploy/conf"))
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

    /** Extracts the archive in [configuration], dropping its single top-level directory. */
    fun extractRuntime(
        name: String,
        configuration: NamedDomainObjectProvider<ResolvableConfiguration>,
        destination: Provider<Directory>,
    ): TaskProvider<Sync> =
        tasks.register(name, Sync::class.java) {
            group = taskGroup
            description = "Extracts a runtime archive"
            // Lambdas stored in tasks must not capture this class: it holds the Project, which the
            // configuration cache cannot serialise. Hence the local copies here and below.
            val archiveOps = archives
            val archive = configuration.flatMap { it.elements }.map { it.single().asFile }
            from(archive.map { file -> if (file.name.endsWith(".zip")) archiveOps.zipTree(file) else archiveOps.tarTree(file) })
            into(destination)
            dropTopLevelDirectory(this)
        }

    fun register(spec: EcosystemSpec) {
        val launchers = registerLaunchers(spec)
        val manifest = registerManifest(spec)
        val sbom = registerSbom(spec)
        val appDir =
            layout.buildDirectory
                .zip(target) { dir, t -> dir.dir("app/${t.id}") }
                .zip(topDirectory) { dir, top -> dir.dir(top) }

        val assembleApp =
            tasks.register("appAssemble", Sync::class.java) {
                group = taskGroup
                description = "Assembles the self-contained app directory for the target"
                into(appDir)
                duplicatesStrategy = DuplicatesStrategy.FAIL
                into("bin") { from(launchers) }
                into("META-INF") {
                    from(manifest)
                    from(sbom)
                }
                into("conf.defaults") { from(extension.configDefaults) }
                spec.contents(this)
                executableBits(this)
            }

        val tar =
            tasks.register("appTar", Tar::class.java) {
                group = taskGroup
                description = "Archives the app as <app>-<version>-<target>.tar.gz"
                from(assembleApp)
                into(topDirectory)
                compression = Compression.GZIP
                // Named the way every Gradle archive task names itself —
                // <base>-<version>-<classifier>.<ext> in build/distributions — rather than by
                // assembling the file name by hand. The classifier is then also what the Maven
                // publication uses, so the two can never disagree.
                archiveBaseName.set(appName)
                archiveVersion.set(versionString)
                archiveClassifier.set(targetId)
                archiveExtension.set("tar.gz")
                destinationDirectory.set(layout.buildDirectory.dir("distributions"))
                executableBits(this)
            }

        val smoke = registerSmokeTest(tar)
        registerRunApp(assembleApp, appDir)
        registerDockerContext(tar)

        // Every archive feeding the app (the application jar included) must be byte-reproducible,
        // so rebuilding a commit yields an identical archive checksum. Gradle 9 does this by default.
        tasks.withType(AbstractArchiveTask::class.java).configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }

        tasks.named("assemble") { dependsOn(tar) }
        tasks.named("check") { dependsOn(smoke) }

        AppPublishing(project, this, components).register(tar, sbom, smoke)
    }

    private fun registerLaunchers(spec: EcosystemSpec) =
        tasks.register("appLaunchers", GenerateLaunchers::class.java) {
            group = taskGroup
            description = "Generates the bin/ launcher for the target"
            appName.set(this@AppWiring.appName)
            windows.set(windowsTarget)
            posixExec.set(spec.posixExec)
            windowsExec.set(spec.windowsExec)
            outputDir.set(workDir.map { it.dir("launchers") })
        }

    private fun registerManifest(spec: EcosystemSpec) =
        tasks.register("appManifest", GenerateAppManifest::class.java) {
            group = taskGroup
            description = "Generates META-INF/app.json"
            appGroup.set(groupString)
            appName.set(this@AppWiring.appName)
            version.set(versionString)
            target.set(targetId)
            ecosystem.set(this@AppWiring.ecosystem.id)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            gitCommit.set(
                providers.gradleProperty(this@AppWiring.ecosystem.property("gitCommit")).orElse(
                    providers.of(GitCommitValueSource::class.java) {
                        parameters.workingDirectory.set(layout.projectDirectory)
                    },
                ),
            )
            outputFile.set(workDir.map { it.file("meta/app.json") })
        }

    private fun registerSbom(spec: EcosystemSpec) =
        tasks.register("appSbom", GenerateSbom::class.java) {
            group = taskGroup
            description = "Generates a CycloneDX SBOM including the runtime"
            appGroup.set(groupString)
            appName.set(this@AppWiring.appName)
            version.set(versionString)
            target.set(targetId)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            val runtime = spec.runtimeName
            runtimePurl.set(spec.runtimeVersion.map { "pkg:generic/$runtime@${it.replace("+", "%2B")}" })
            mavenComponents.convention(emptyList())
            spec.sbom(this)
            outputFile.set(workDir.map { it.file("meta/sbom.cdx.json") })
        }

    private fun registerSmokeTest(tar: TaskProvider<Tar>) =
        tasks.register("appSmokeTest", AppSmokeTest::class.java) {
            group = VERIFICATION_GROUP
            description = "Extracts the archive, starts it and waits for its healthcheck"
            archive.set(tar.flatMap { it.archiveFile })
            appName.set(this@AppWiring.appName)
            expectedVersion.set(versionString)
            windows.set(windowsTarget)
            healthTimeout.set(extension.smokeTestTimeout)
            resultFile.set(workDir.map { it.file("smoke-test.txt") })
            val crossBuild = cross
            val enabled = extension.smokeTestEnabled
            onlyIf("the archive targets this build host") { !crossBuild.get() }
            onlyIf("${this@AppWiring.ecosystem.extensionName}.smokeTestEnabled is true") { enabled.get() }
        }

    private fun registerRunApp(
        assembleApp: TaskProvider<Sync>,
        appDir: Provider<Directory>,
    ) {
        tasks.register("runApp", RunApp::class.java) {
            group = APPLICATION_GROUP
            description = "Runs the assembled app in the foreground (bin/<app> run)"
            dependsOn(assembleApp)
            this.appDir.set(appDir)
            appName.set(this@AppWiring.appName)
            windows.set(windowsTarget)
            args.convention(
                providers.gradleProperty("args").map { it.split(" ").filter(String::isNotBlank) }.orElse(emptyList()),
            )
            val crossBuild = cross
            onlyIf("the app targets this build host") { !crossBuild.get() }
        }
    }

    private fun registerDockerContext(tar: TaskProvider<Tar>) {
        val template =
            AppWiring::class.java.getResource("/io/github/fiftieshousewife/appcore/docker/Dockerfile")?.readText()
                ?: error("Dockerfile template missing from the app-core jar")
        val archiveOps = archives
        val dockerfile =
            tasks.register("appDockerfile", WriteTextFile::class.java) {
                group = taskGroup
                description = "Writes the container Dockerfile (override with src/deploy/docker/Dockerfile)"
                text.set(
                    providers
                        .fileContents(layout.projectDirectory.file("src/deploy/docker/Dockerfile"))
                        .asText
                        .orElse(appName.map { name -> template.replace("@APP_NAME@", name) }),
                )
                outputFile.set(workDir.map { it.file("docker/Dockerfile") })
            }
        tasks.register("appDockerContext", Sync::class.java) {
            group = taskGroup
            description = "Prepares a container build context from the archive: Dockerfile + app/"
            from(dockerfile)
            // Not `into("app")`: inside a child spec the path already starts with `app/`, so dropping
            // the first segment would drop that instead of the archive's `<app>-<version>/`.
            from(tar.flatMap { it.archiveFile }.map { archiveOps.tarTree(it) }) {
                dropTopLevelDirectory(this, replacement = "app")
            }
            into(layout.buildDirectory.dir("docker-context"))
        }
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
        private fun dropTopLevelDirectory(
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
