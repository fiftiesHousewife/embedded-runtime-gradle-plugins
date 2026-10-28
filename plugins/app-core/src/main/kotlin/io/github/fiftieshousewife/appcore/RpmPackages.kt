package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateRpm
import io.github.fiftieshousewife.appcore.tasks.PublishRpm
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import java.net.URI

/**
 * `appRpm<Target>` for each Linux target when `rpm { enabled = true }`, `appRpm` for all of them,
 * and the upload `publish` runs when there is a YUM repository: `<javaApp|nodeApp>.rpmRepositoryUrl`,
 * else the binary repository's `binaryRepo.repo.rpm`.
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

    private val repositoryUrl =
        providers.gradleProperty(wiring.ecosystem.property("rpmRepositoryUrl")).map(URI::create)
            .let { property -> wiring.binaryRepo?.let { repo -> property.orElse(repo.rpmUrl) } ?: property }

    private val publish: TaskProvider<PublishRpm> =
        tasks.register("appPublishRpm", PublishRpm::class.java) {
            description = "Uploads every RPM to the YUM repository, as part of publish"
            repositoryUrl.set(this@RpmPackages.repositoryUrl)
            login.set(wiring.binaryRepo?.login(providers) ?: BinaryRepository.identityLogin(providers))
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
    fun register(built: List<TargetTasks>, guards: List<TaskProvider<out Task>>) {
        rpm.enabled.finalizeValue()
        if (!rpm.enabled.get()) return
        val summary = project.description
        val packages = built.filter { it.target.os == Os.LINUX }.map { registerTarget(it, summary) }
        all.configure { dependsOn(packages) }
        tasks.named("assemble") { dependsOn(all) }
        publish.configure {
            this.packages.from(packages)
            dependsOn(guards)
        }
        if (repositoryUrl.isPresent) tasks.named("publish") { dependsOn(publish) }
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
