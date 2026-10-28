package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.EcosystemSpec
import io.github.fiftieshousewife.appcore.Launchers
import io.github.fiftieshousewife.javaapp.tasks.CustomiseRuntime
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar

/**
 * What a Java app puts in its archive: the customised runtime in `runtime/`, and the application jar
 * with its dependencies in `lib/`, as the `application` plugin lays them out.
 */
internal class JavaEcosystem(private val project: Project, private val jdkVersion: Provider<String>) {
    private val jar = project.tasks.named("jar", Jar::class.java)
    private val runtimeClasspath = project.configurations.named("runtimeClasspath")
    private val resolvedArtifacts = runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }

    fun spec(runtime: TaskProvider<CustomiseRuntime>): EcosystemSpec = EcosystemSpec(
        runtimeName = JdkSources.from(project.providers).distribution.id,
        runtimeVersion = jdkVersion,
        launchers = Launchers.StartScripts(project.tasks.named("startScripts")),
        contents = {
            into("runtime") { from(runtime) }
            into("lib") {
                from(jar)
                from(runtimeClasspath)
            }
        },
        sbom = {
            mavenComponents.set(resolvedArtifacts.map { artifacts -> artifacts.mapNotNull(::coordinates) })
            mavenFiles.from(resolvedArtifacts.map { artifacts -> artifacts.map { it.file } })
        },
    )

    /** `group:module:version:fileName` for an artifact from a repository; null for a project or file. */
    private fun coordinates(artifact: ResolvedArtifactResult): String? =
        (artifact.id.componentIdentifier as? ModuleComponentIdentifier)
            ?.let { "${it.group}:${it.module}:${it.version}:${artifact.file.name}" }
}
