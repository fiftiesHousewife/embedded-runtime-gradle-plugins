package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.EcosystemSpec
import io.github.fiftieshousewife.appcore.Launchers
import io.github.fiftieshousewife.javaapp.tasks.CustomiseRuntime
import io.github.fiftieshousewife.javaapp.tasks.JlinkRuntime
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar

/**
 * What a Java app puts in its archives: the customised runtime in `runtime/`, and in `lib/`, as the `application`
 * plugin lays them out, its own jars in the app archive and third-party ones in the libraries archive. A project
 * dependency's jar is named by its version, so it goes with the app's.
 */
internal class JavaEcosystem(private val project: Project, private val jdkVersion: Provider<String>) {
    private val jar = project.tasks.named("jar", Jar::class.java)
    private val runtimeClasspath = project.configurations.named("runtimeClasspath")
    private val resolvedArtifacts = runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }
    private val projectJars = project.files(
        runtimeClasspath.map { classpath ->
            classpath.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier } }.files
        },
    )
    private val thirdPartyJars = project.files(runtimeClasspath).minus(projectJars)

    fun spec(jlink: TaskProvider<JlinkRuntime>, runtime: TaskProvider<CustomiseRuntime>): EcosystemSpec = EcosystemSpec(
        runtimeName = JdkSources.from(project.providers).distribution.id,
        runtimeVersion = jdkVersion,
        launchers = Launchers.StartScripts(project.tasks.named("startScripts")),
        runtime = runtime,
        contents = {
            into("lib") {
                from(jar)
                from(projectJars)
            }
        },
        librariesDirectory = "lib",
        libraries = { from(thirdPartyJars) },
        sbom = {
            mavenComponents.set(resolvedArtifacts.map { artifacts -> artifacts.mapNotNull(::coordinates) })
            mavenFiles.from(resolvedArtifacts.map { artifacts -> artifacts.map { it.file } })
        },
        fingerprint = {
            entries.put("modules", jlink.flatMap { it.modules }.map { it.joinToString(",") })
            entries.put("jlinkOptions", jlink.flatMap { it.options }.map { it.joinToString(" ") })
            entries.put("linkedOnWindows", jlink.flatMap { it.windowsHost }.map(Boolean::toString))
            // The JDK itself, so one re-released under the same version is a new runtime, not a duplicate.
            sources.from(project.fileTree(jlink.flatMap { it.targetJdk }) { include(JDK_CONTENTS) })
            sources.from(jlink.flatMap { it.targetJmods }.map { listOf(it) }.orElse(emptyList()))
            sources.from(runtime.flatMap { it.outputDir.file(CustomiseRuntime.RECORD) })
            sources.from(runtime.flatMap { it.overlay }.map { listOf(it) }.orElse(emptyList()))
        },
    )

    private companion object {
        val JDK_CONTENTS = listOf("**/jmods/*.jmod", "**/lib/modules")
    }
}

/**
 * `group:module:version:fileName` for an artifact from a repository; null for a project or file. Outside the class,
 * as a build dependency a task makes keeps the SBOM's providers for the configuration cache, and they mustn't hold it.
 */
private fun coordinates(artifact: ResolvedArtifactResult): String? =
    (artifact.id.componentIdentifier as? ModuleComponentIdentifier)
        ?.let { "${it.group}:${it.module}:${it.version}:${artifact.file.name}" }
