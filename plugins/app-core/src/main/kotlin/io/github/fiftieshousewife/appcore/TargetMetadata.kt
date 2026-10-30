package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateAppManifest
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/** Each target's `META-INF/app.json` and built-in SBOM. */
internal class TargetMetadata(project: Project, private val wiring: AppWiring) {
    private val tasks = project.tasks
    private val extension = wiring.extension
    private val workDir = wiring.workDir
    private val taskGroup = wiring.taskGroup
    private val appName: Provider<String> get() = extension.appName
    private val versionString: Provider<String> = wiring.version
    private val groupString: Provider<String> = project.providers.provider { project.group.toString() }

    fun registerManifest(target: Target, spec: EcosystemSpec) =
        tasks.register("appManifest${target.taskSuffix}", GenerateAppManifest::class.java) {
            group = taskGroup
            description = "Generates META-INF/app.json for ${target.id}"
            appGroup.set(groupString)
            appName.set(this@TargetMetadata.appName)
            version.set(versionString)
            this.target.set(target.id)
            ecosystem.set(wiring.ecosystem.id)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            gitCommit.set(wiring.gitCommit)
            outputFile.set(workDir.map { it.file("meta/${target.id}/app.json") })
        }

    fun registerSbom(target: Target, spec: EcosystemSpec) =
        tasks.register("appSbom${target.taskSuffix}", GenerateSbom::class.java) {
            val replaced = extension.sbom
            onlyIf("the project supplies no SBOM of its own (sbom)") { !replaced.isPresent }
            group = taskGroup
            description = "Generates a CycloneDX SBOM for ${target.id}, including the runtime"
            appGroup.set(groupString)
            appName.set(this@TargetMetadata.appName)
            version.set(versionString)
            this.target.set(target.id)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            mavenComponents.convention(emptyList())
            spec.sbom(this)
            outputFile.set(workDir.map { it.file("meta/${target.id}/sbom.cdx.json") })
        }
}
