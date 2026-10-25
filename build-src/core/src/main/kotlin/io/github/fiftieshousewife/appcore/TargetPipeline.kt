package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateAppManifest
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import io.github.fiftieshousewife.appcore.tasks.GitCommitValueSource
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

/** The tasks built once per target: launchers, manifest and SBOM, assembled into a directory and archived. */
internal class TargetPipeline(
    private val project: Project,
    private val wiring: AppWiring,
) {
    private val providers = project.providers
    private val layout = project.layout
    private val tasks = project.tasks
    private val extension = wiring.extension
    private val workDir = wiring.workDir
    private val taskGroup = wiring.taskGroup

    private val appName: Provider<String> get() = extension.appName
    private val versionString: Provider<String> = providers.provider { project.version.toString() }
    private val groupString: Provider<String> = providers.provider { project.group.toString() }

    /** `<app>-<version>`: the archive's single top-level directory. */
    private val topDirectory: Provider<String> get() = appName.zip(versionString) { n, v -> "$n-$v" }

    fun register(
        target: Target,
        spec: EcosystemSpec,
    ): TargetTasks {
        val suffix = target.taskSuffix
        val launchers = registerLaunchers(target, spec)
        val manifest = registerManifest(target, spec)
        val sbom = registerSbom(target, spec)
        val appDir = layout.buildDirectory.zip(topDirectory) { dir, top -> dir.dir("app/${target.id}/$top") }

        val assembleApp =
            tasks.register("appAssemble$suffix", Sync::class.java) {
                group = taskGroup
                description = "Assembles the self-contained app directory for ${target.id}"
                into(appDir)
                duplicatesStrategy = DuplicatesStrategy.FAIL
                into("bin") { from(launchers) }
                into("META-INF") {
                    from(manifest)
                    from(sbom)
                }
                into("conf.defaults") { from(extension.configDefaults) }
                spec.contents(this)
                AppWiring.executableBits(this)
            }

        val tar =
            tasks.register("appTar$suffix", Tar::class.java) {
                group = taskGroup
                description = "Archives the app for ${target.id} as <app>-<version>-${target.id}.tar.gz"
                from(assembleApp)
                into(topDirectory)
                compression = Compression.GZIP
                // Named the way every Gradle archive task names itself —
                // <base>-<version>-<classifier>.<ext> in build/distributions — rather than by
                // assembling the file name by hand. The classifier is then also what the Maven
                // publication uses, so the two can never disagree.
                archiveBaseName.set(appName)
                archiveVersion.set(versionString)
                archiveClassifier.set(target.id)
                archiveExtension.set("tar.gz")
                destinationDirectory.set(layout.buildDirectory.dir("distributions"))
                AppWiring.executableBits(this)
            }
        return TargetTasks(target, assembleApp, tar, sbom)
    }

    private fun registerLaunchers(
        target: Target,
        spec: EcosystemSpec,
    ) = tasks.register("appLaunchers${target.taskSuffix}", GenerateLaunchers::class.java) {
        group = taskGroup
        description = "Generates the bin/ launcher for ${target.id}"
        appName.set(this@TargetPipeline.appName)
        windows.set(target.isWindows)
        posixExec.set(spec.posixExec)
        windowsExec.set(spec.windowsExec)
        outputDir.set(workDir.map { it.dir("launchers/${target.id}") })
    }

    private fun registerManifest(
        target: Target,
        spec: EcosystemSpec,
    ) = tasks.register("appManifest${target.taskSuffix}", GenerateAppManifest::class.java) {
        group = taskGroup
        description = "Generates META-INF/app.json for ${target.id}"
        appGroup.set(groupString)
        appName.set(this@TargetPipeline.appName)
        version.set(versionString)
        this.target.set(target.id)
        ecosystem.set(wiring.ecosystem.id)
        runtimeName.set(spec.runtimeName)
        runtimeVersion.set(spec.runtimeVersion)
        gitCommit.set(
            providers.gradleProperty(wiring.ecosystem.property("gitCommit")).orElse(
                providers.of(GitCommitValueSource::class.java) {
                    parameters.workingDirectory.set(layout.projectDirectory)
                },
            ),
        )
        outputFile.set(workDir.map { it.file("meta/${target.id}/app.json") })
    }

    private fun registerSbom(
        target: Target,
        spec: EcosystemSpec,
    ) = tasks.register("appSbom${target.taskSuffix}", GenerateSbom::class.java) {
        group = taskGroup
        description = "Generates a CycloneDX SBOM for ${target.id}, including the runtime"
        appGroup.set(groupString)
        appName.set(this@TargetPipeline.appName)
        version.set(versionString)
        this.target.set(target.id)
        runtimeName.set(spec.runtimeName)
        runtimeVersion.set(spec.runtimeVersion)
        val runtime = spec.runtimeName
        runtimePurl.set(spec.runtimeVersion.map { "pkg:generic/$runtime@${it.replace("+", "%2B")}" })
        mavenComponents.convention(emptyList())
        spec.sbom(this)
        outputFile.set(workDir.map { it.file("meta/${target.id}/sbom.cdx.json") })
    }
}
