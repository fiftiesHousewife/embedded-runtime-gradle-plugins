package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateRpm
import io.github.fiftieshousewife.appcore.tasks.PublishRpm
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * `appRpm<Target>` for each Linux target when `rpm { enabled = true }`, `appRpm` for all of them,
 * and `appPublishRpm`, which `publish` runs when there is a repository to upload to.
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

    private val publish: TaskProvider<PublishRpm> =
        tasks.register("appPublishRpm", PublishRpm::class.java) {
            group = "publishing"
            description = "Uploads every RPM to the YUM repository rpm.repositoryUrl"
            repositoryUrl.set(rpm.repositoryUrl)
        }

    fun applyConventions() {
        val appName = wiring.extension.appName
        rpm.enabled.convention(
            providers.gradleProperty(wiring.ecosystem.property("rpm")).map(String::toBoolean).orElse(false),
        )
        rpm.packageName.convention(appName)
        rpm.release.convention("1")
        rpm.summary.convention(appName.map { "$it with its own runtime" })
        rpm.license.convention("LicenseRef-Proprietary")
        rpm.user.convention(rpm.packageName)
        rpm.systemd.convention(true)
        wiring.artifactory?.let { rpm.repositoryUrl.convention(it.rpmUrl) }
    }

    /** Registers the RPM tasks for [built], once targets and settings are final. */
    fun register(built: List<TargetTasks>, guards: List<TaskProvider<out Task>>) {
        rpm.enabled.finalizeValue()
        if (!rpm.enabled.get()) return
        val packages = built.filter { it.target.os == Os.LINUX }.map(::registerTarget)
        all.configure { dependsOn(packages) }
        tasks.named("assemble") { dependsOn(all) }
        publish.configure {
            this.packages.from(packages)
            dependsOn(guards)
        }
        if (rpm.repositoryUrl.isPresent) tasks.named("publish") { dependsOn(publish) }
    }

    private fun registerTarget(built: TargetTasks) =
        tasks.register("appRpm${built.target.taskSuffix}", GenerateRpm::class.java) {
            group = wiring.taskGroup
            description = "Packages the app for ${built.target.id} as an RPM"
            appDirectory.set(project.layout.dir(built.assemble.map { it.destinationDir }))
            dependsOn(built.assemble)
            layout.set(layoutOf(built.target))
            license.set(rpm.license)
            vendor.set(rpm.vendor)
            url.set(rpm.url)
            systemd.set(rpm.systemd)
            buildTime.set(providers.environmentVariable("SOURCE_DATE_EPOCH").map(String::toLong))
            val fileName = layout.map { it.fileName }
            outputFile.set(project.layout.buildDirectory.zip(fileName) { dir, name -> dir.file("distributions/$name") })
        }

    private fun layoutOf(target: Target) = rpm.packageName.map { name ->
        RpmLayout(
            name = name,
            version = wiring.version.get(),
            release = rpm.release.get(),
            target = target,
            user = rpm.user.get(),
            launcher = wiring.extension.appName.get(),
            summary = rpm.summary.get(),
        )
    }
}
