package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateAppManifest
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

/** The tasks built once per target: launchers, manifest and SBOM, assembled into a directory and archived. */
internal class TargetPipeline(private val project: Project, private val wiring: AppWiring) {
    private val providers = project.providers
    private val layout = project.layout
    private val tasks = project.tasks
    private val extension = wiring.extension
    private val workDir = wiring.workDir
    private val taskGroup = wiring.taskGroup

    private val appName: Provider<String> get() = extension.appName
    private val versionString: Provider<String> = wiring.version
    private val groupString: Provider<String> = providers.provider { project.group.toString() }

    /** `<app>-<version>`: the archive's single top-level directory. */
    private val topDirectory: Provider<String> get() = appName.zip(versionString) { n, v -> "$n-$v" }

    fun register(target: Target, spec: EcosystemSpec): TargetTasks {
        val parts = Parts(registerLaunchers(target, spec), registerManifest(target, spec), registerSbom(target, spec))
        val assemble = registerAssemble(target, spec, parts)
        return TargetTasks(target, assemble, registerArchive(target, assemble), parts.sbom)
    }

    /** The generated pieces every target's directory holds besides the ecosystem's own content. */
    private class Parts(
        val launchers: TaskProvider<GenerateLaunchers>,
        val manifest: TaskProvider<GenerateAppManifest>,
        val sbom: TaskProvider<GenerateSbom>,
    )

    private fun registerAssemble(target: Target, spec: EcosystemSpec, parts: Parts) =
        tasks.register("appAssemble${target.taskSuffix}", Sync::class.java) {
            group = taskGroup
            description = "Assembles the self-contained app directory for ${target.id}"
            into(layout.buildDirectory.zip(topDirectory) { dir, top -> dir.dir("app/${target.id}/$top") })
            duplicatesStrategy = DuplicatesStrategy.FAIL
            into("bin") { from(parts.launchers) }
            into("META-INF") {
                from(parts.manifest)
                from(parts.sbom)
            }
            into("conf.defaults") { from(extension.configDefaults) }
            spec.contents(this)
            AppCopySpecs.executableBits(this)
        }

    /**
     * Named the way every Gradle archive task names itself — `<base>-<version>-<classifier>.<ext>` in
     * `build/distributions` — so the classifier is also what the Maven publication uses, and the two
     * can never disagree.
     */
    private fun registerArchive(target: Target, assemble: TaskProvider<Sync>): TaskProvider<out AbstractArchiveTask> {
        val format = extension.archiveFormat.get()
        val type = if (format == ArchiveFormat.ZIP) Zip::class.java else Tar::class.java
        return tasks.register("appArchive${target.taskSuffix}", type) {
            group = taskGroup
            description = "Archives the app for ${target.id} as <app>-<version>-${target.id}.$format"
            from(assemble)
            into(topDirectory)
            if (this is Tar) compression = if (format == ArchiveFormat.TAR_GZ) Compression.GZIP else Compression.NONE
            archiveBaseName.set(appName)
            archiveVersion.set(versionString)
            archiveClassifier.set(target.id)
            archiveExtension.set(format.extension)
            destinationDirectory.set(layout.buildDirectory.dir("distributions"))
            AppCopySpecs.executableBits(this)
        }
    }

    private fun registerLaunchers(target: Target, spec: EcosystemSpec) =
        tasks.register("appLaunchers${target.taskSuffix}", GenerateLaunchers::class.java) {
            group = taskGroup
            description = "Generates the bin/ launcher for ${target.id}"
            appName.set(this@TargetPipeline.appName)
            windows.set(target.isWindows)
            posixExec.set(spec.posixExec)
            windowsExec.set(spec.windowsExec)
            lockedOptions.set(extension.runtimeOptions.map { it == RuntimeOptions.LOCKED })
            outputDir.set(workDir.map { it.dir("launchers/${target.id}") })
        }

    private fun registerManifest(target: Target, spec: EcosystemSpec) =
        tasks.register("appManifest${target.taskSuffix}", GenerateAppManifest::class.java) {
            group = taskGroup
            description = "Generates META-INF/app.json for ${target.id}"
            appGroup.set(groupString)
            appName.set(this@TargetPipeline.appName)
            version.set(versionString)
            this.target.set(target.id)
            ecosystem.set(wiring.ecosystem.id)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            gitCommit.set(wiring.gitCommit)
            outputFile.set(workDir.map { it.file("meta/${target.id}/app.json") })
        }

    private fun registerSbom(target: Target, spec: EcosystemSpec) =
        tasks.register("appSbom${target.taskSuffix}", GenerateSbom::class.java) {
            group = taskGroup
            description = "Generates a CycloneDX SBOM for ${target.id}, including the runtime"
            appGroup.set(groupString)
            appName.set(this@TargetPipeline.appName)
            version.set(versionString)
            this.target.set(target.id)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            mavenComponents.convention(emptyList())
            spec.sbom(this)
            outputFile.set(workDir.map { it.file("meta/${target.id}/sbom.cdx.json") })
        }
}
