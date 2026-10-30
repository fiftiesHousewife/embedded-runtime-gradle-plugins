package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateRpm
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * `appRpm<Target>` for each Linux target when `rpm { enabled = true }`, and `appRpm` for all of them. An RPM is
 * built, not published: its payload times are the build's own, so a republished runtime would never deduplicate.
 */
internal class RpmPackages(private val project: Project, private val wiring: AppWiring) {
    private val providers = project.providers
    private val tasks = project.tasks
    private val rpm = wiring.extension.rpm

    private val all: TaskProvider<Task> =
        tasks.register("appRpm") {
            group = wiring.taskGroup
            description = "Builds the RPM for every Linux target (javaApp/nodeApp rpm { enabled = true })"
        }

    fun applyConventions() {
        val appName = wiring.extension.appName
        rpm.enabled.convention(
            providers.gradleProperty(wiring.ecosystem.property("rpm")).map(String::toBoolean).orElse(false),
        )
        rpm.user.convention(appName)
        rpm.systemd.convention(true)
    }

    /** Registers the RPM tasks for [built], once targets and settings are final. */
    fun register(built: List<TargetTasks>) {
        rpm.enabled.finalizeValue()
        if (!rpm.enabled.get()) return
        val summary = project.description
        val packages = built.filter { it.target.os == Os.LINUX }.map { registerTarget(it, summary) }
        all.configure { dependsOn(packages) }
        tasks.named("assemble") { dependsOn(all) }
    }

    private fun registerTarget(built: TargetTasks, summary: String?) =
        tasks.register("appRpm${built.target.taskSuffix}", GenerateRpm::class.java) {
            group = wiring.taskGroup
            description = "Packages the app for ${built.target.id} as an RPM"
            appDirectory.set(project.layout.dir(built.assemble.map { it.destinationDir }))
            dependsOn(built.assemble)
            layout.set(layoutOf(built.target, summary))
            license.set(wiring.extension.license)
            url.set(wiring.facts.sourceUrl)
            systemd.set(rpm.systemd)
            buildTime.set(providers.environmentVariable("SOURCE_DATE_EPOCH").map(String::toLong))
            val fileName = layout.map { it.fileName }
            outputFile.set(project.layout.buildDirectory.zip(fileName) { dir, name -> dir.file("distributions/$name") })
        }

    private fun layoutOf(target: Target, summary: String?) = wiring.extension.appName.map { name ->
        RpmLayout(
            name = name,
            version = wiring.version.get(),
            release = "1",
            target = target,
            user = rpm.user.get(),
            launcher = name,
            summary = summary ?: "$name with its own runtime",
        )
    }
}
