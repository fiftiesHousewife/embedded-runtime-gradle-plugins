package io.github.fiftieshousewife.platform.bundle

import io.github.fiftieshousewife.platform.PlatformProperties
import io.github.fiftieshousewife.platform.PlatformRepositories
import io.github.fiftieshousewife.platform.bundle.tasks.BundleSmokeTest
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateBundleManifest
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateLaunchers
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateSbom
import io.github.fiftieshousewife.platform.bundle.tasks.GitCommitValueSource
import io.github.fiftieshousewife.platform.bundle.tasks.RunBundle
import io.github.fiftieshousewife.platform.bundle.tasks.WriteTextFile
import io.github.fiftieshousewife.platform.runtime.Platform
import io.github.fiftieshousewife.platform.runtime.RuntimeArtifact
import io.github.fiftieshousewife.platform.settings.PlatformSettingsPlugin
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
import java.time.Duration

/** What an ecosystem plugin contributes to the shared bundle pipeline. */
@Suppress("LongParameterList") // A parameter object: naming every contribution at the call site is its purpose.
internal class EcosystemSpec(
    val ecosystem: String,
    val runtimeName: String,
    val runtimeVersion: Provider<String>,
    val posixExec: Provider<String>,
    val windowsExec: Provider<String>,
    /** Adds the ecosystem's own content (`runtime/`, plus `lib/` or `app/`) to the assembled bundle. */
    val contents: Sync.() -> Unit,
    /** Feeds library components into the SBOM task. */
    val sbom: GenerateSbom.() -> Unit,
)

/**
 * The ecosystem-independent pipeline shared by the Java and Node plugins:
 *
 * ```
 * bundleLaunchers ─┐
 * bundleManifest  ─┼─► bundleAssemble ─► bundleTar ─► bundleSmokeTest ─► publish
 * bundleSbom      ─┘                         └──────► bundleDockerContext
 * ```
 *
 * Every archive, whatever the target, is a `.tar.gz` with one top-level directory
 * `<app>-<version>/`. Windows 10+ extracts it with the built-in `tar.exe`.
 */
internal class BundleWiring(
    private val project: Project,
    val extension: BundleExtension,
    private val archives: ArchiveOperations,
    private val components: SoftwareComponentFactory,
) {
    private val providers = project.providers
    private val layout = project.layout
    private val tasks = project.tasks

    val repos: PlatformRepositories = PlatformRepositories.from(providers)
    val host: Platform = Platform.host()
    val target: Provider<Platform> get() = extension.targetPlatform
    val cross: Provider<Boolean> get() = target.map { it != host }
    val windowsTarget: Provider<Boolean> get() = target.map { it.isWindows }
    val workDir: Provider<Directory> = layout.buildDirectory.dir("bundle-work")

    private val appName: Provider<String> get() = extension.appName
    private val versionString: Provider<String> = providers.provider { project.version.toString() }
    private val groupString: Provider<String> = providers.provider { project.group.toString() }
    private val platformId: Provider<String> get() = target.map { it.id }

    /** `<app>-<version>`: the archive's single top-level directory. */
    private val topDirectory: Provider<String> get() = appName.zip(versionString) { n, v -> "$n-$v" }

    fun applyConventions() {
        requireSettingsPlugin(project)
        extension.appName.convention(project.name)
        extension.targetPlatform.convention(
            providers.gradleProperty(PlatformProperties.TARGET_PLATFORM).map(Platform::parse).orElse(host),
        )
        extension.configDefaults.convention(layout.projectDirectory.dir("src/bundle/conf"))
        extension.smokeTestEnabled.convention(true)
        extension.smokeTestTimeout.convention(DEFAULT_SMOKE_TEST_TIMEOUT)
        extension.allowUntestedPublish.convention(
            providers.gradleProperty(PlatformProperties.ALLOW_UNTESTED_PUBLISH).map(String::toBoolean).orElse(false),
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
            description = "Runtime archive resolved from the platform runtime repository"
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
            group = BUILD_GROUP
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
        val bundleDir =
            layout.buildDirectory
                .zip(target) { dir, p -> dir.dir("bundle/${p.id}") }
                .zip(topDirectory) { dir, top -> dir.dir(top) }

        val assembleBundle =
            tasks.register("bundleAssemble", Sync::class.java) {
                group = BUILD_GROUP
                description = "Assembles the self-contained bundle directory for the target platform"
                into(bundleDir)
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
            tasks.register("bundleTar", Tar::class.java) {
                group = BUILD_GROUP
                description = "Archives the bundle as <app>-<version>-<platform>.tar.gz"
                from(assembleBundle)
                into(topDirectory)
                compression = Compression.GZIP
                // Named the way every Gradle archive task names itself —
                // <base>-<version>-<classifier>.<ext> in build/distributions — rather than by
                // assembling the file name by hand. The classifier is then also what the Maven
                // publication uses, so the two can never disagree.
                archiveBaseName.set(appName)
                archiveVersion.set(versionString)
                archiveClassifier.set(platformId)
                archiveExtension.set("tar.gz")
                destinationDirectory.set(layout.buildDirectory.dir("distributions"))
                executableBits(this)
            }

        val smoke = registerSmokeTest(tar)
        registerRunBundle(assembleBundle, bundleDir)
        registerDockerContext(tar)

        // Every archive feeding the bundle (the application jar included) must be byte-reproducible,
        // so rebuilding a commit yields an identical bundle checksum. Gradle 9 does this by default.
        tasks.withType(AbstractArchiveTask::class.java).configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }

        tasks.named("assemble") { dependsOn(tar) }
        tasks.named("check") { dependsOn(smoke) }

        BundlePublishing(project, extension, components, repos, target, cross).register(tar, sbom, smoke)
    }

    private fun registerLaunchers(spec: EcosystemSpec) =
        tasks.register("bundleLaunchers", GenerateLaunchers::class.java) {
            group = BUILD_GROUP
            description = "Generates the bin/ launcher for the target platform"
            appName.set(this@BundleWiring.appName)
            windows.set(windowsTarget)
            posixExec.set(spec.posixExec)
            windowsExec.set(spec.windowsExec)
            outputDir.set(workDir.map { it.dir("launchers") })
        }

    private fun registerManifest(spec: EcosystemSpec) =
        tasks.register("bundleManifest", GenerateBundleManifest::class.java) {
            group = BUILD_GROUP
            description = "Generates META-INF/bundle.json"
            bundleGroup.set(groupString)
            appName.set(this@BundleWiring.appName)
            version.set(versionString)
            platform.set(platformId)
            ecosystem.set(spec.ecosystem)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            gitCommit.set(
                providers.gradleProperty(PlatformProperties.GIT_COMMIT).orElse(
                    providers.of(GitCommitValueSource::class.java) {
                        parameters.workingDirectory.set(layout.projectDirectory)
                    },
                ),
            )
            outputFile.set(workDir.map { it.file("meta/bundle.json") })
        }

    private fun registerSbom(spec: EcosystemSpec) =
        tasks.register("bundleSbom", GenerateSbom::class.java) {
            group = BUILD_GROUP
            description = "Generates a CycloneDX SBOM including the bundled runtime"
            bundleGroup.set(groupString)
            appName.set(this@BundleWiring.appName)
            version.set(versionString)
            platform.set(platformId)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            val runtime = spec.runtimeName
            runtimePurl.set(spec.runtimeVersion.map { "pkg:generic/$runtime@${it.replace("+", "%2B")}" })
            mavenComponents.convention(emptyList())
            spec.sbom(this)
            outputFile.set(workDir.map { it.file("meta/sbom.cdx.json") })
        }

    private fun registerSmokeTest(tar: TaskProvider<Tar>) =
        tasks.register("bundleSmokeTest", BundleSmokeTest::class.java) {
            group = VERIFICATION_GROUP
            description = "Extracts the archive, starts it and waits for its healthcheck"
            archive.set(tar.flatMap { it.archiveFile })
            appName.set(this@BundleWiring.appName)
            expectedVersion.set(versionString)
            windows.set(windowsTarget)
            healthTimeout.set(extension.smokeTestTimeout)
            resultFile.set(workDir.map { it.file("smoke-test.txt") })
            val crossBuild = cross
            val enabled = extension.smokeTestEnabled
            onlyIf("the bundle targets this build host") { !crossBuild.get() }
            onlyIf("bundle.smokeTestEnabled is true") { enabled.get() }
        }

    private fun registerRunBundle(
        assembleBundle: TaskProvider<Sync>,
        bundleDir: Provider<Directory>,
    ) {
        tasks.register("runBundle", RunBundle::class.java) {
            group = APPLICATION_GROUP
            description = "Runs the assembled bundle in the foreground (bin/<app> run)"
            dependsOn(assembleBundle)
            this.bundleDir.set(bundleDir)
            appName.set(this@BundleWiring.appName)
            windows.set(windowsTarget)
            args.convention(
                providers.gradleProperty("args").map { it.split(" ").filter(String::isNotBlank) }.orElse(emptyList()),
            )
            val crossBuild = cross
            onlyIf("the bundle targets this build host") { !crossBuild.get() }
        }
    }

    private fun registerDockerContext(tar: TaskProvider<Tar>) {
        val template =
            BundleWiring::class.java.getResource("/io/github/fiftieshousewife/platform/docker/Dockerfile")?.readText()
                ?: error("Dockerfile template missing from plugin jar")
        val archiveOps = archives
        val dockerfile =
            tasks.register("bundleDockerfile", WriteTextFile::class.java) {
                group = BUILD_GROUP
                description = "Writes the container Dockerfile (override with src/bundle/docker/Dockerfile)"
                text.set(
                    providers
                        .fileContents(layout.projectDirectory.file("src/bundle/docker/Dockerfile"))
                        .asText
                        .orElse(appName.map { name -> template.replace("@APP_NAME@", name) }),
                )
                outputFile.set(workDir.map { it.file("docker/Dockerfile") })
            }
        tasks.register("bundleDockerContext", Sync::class.java) {
            group = BUILD_GROUP
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
        const val BUILD_GROUP = "bundle"
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

        private fun requireSettingsPlugin(project: Project) {
            if (!project.gradle.extensions.extraProperties
                    .has(PlatformSettingsPlugin.APPLIED_MARKER)
            ) {
                throw GradleException(
                    "Apply the settings plugin in settings.gradle.kts first:\n" +
                        "  plugins { id(\"io.github.fiftieshousewife.platform.settings\") version \"<version>\" }\n" +
                        "It configures the Maven, plugin and runtime repositories this plugin resolves from.",
                )
            }
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
