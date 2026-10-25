package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/**
 * `appDockerContext`: a container build context holding a Dockerfile (the project's own if it has
 * one, else a generated one) and a Linux archive extracted under `app/`.
 */
internal class DockerContext(
    private val project: Project,
    private val wiring: AppWiring,
    private val archives: ArchiveOperations,
) {
    private val extension = wiring.extension

    private val task: TaskProvider<Sync> = register()

    /** Containers run Linux: use the Linux archive for this machine's CPU, else the first Linux one. */
    fun useLinuxArchive(built: List<TargetTasks>) {
        val linux = built.filter { it.target.os == Os.LINUX }
        val chosen = linux.firstOrNull { it.target.arch == wiring.host.arch } ?: linux.firstOrNull()
        val archiveOps = archives
        task.configure {
            if (chosen == null) {
                val targets = "${wiring.ecosystem.extensionName}.targets"
                val message = "appDockerContext needs a Linux archive; add a linux target to $targets"
                doFirst { throw GradleException(message) }
                return@configure
            }
            // Not `into("app")`: inside a child spec the path already starts with `app/`, so dropping
            // the first segment would drop that instead of the archive's `<app>-<version>/`.
            from(chosen.archive.flatMap { it.archiveFile }.map { archiveOps.archiveTree(it.asFile) }) {
                AppCopySpecs.dropTopLevelDirectory(this, replacement = "app")
            }
        }
    }

    private fun register(): TaskProvider<Sync> {
        val template =
            DockerContext::class.java.getResource("/io/github/fiftieshousewife/appcore/docker/Dockerfile")?.readText()
                ?: error("Dockerfile template missing from the app-core jar")
        val dockerfile =
            project.tasks.register("appDockerfile", WriteTextFile::class.java) {
                group = wiring.taskGroup
                description = "Writes the container Dockerfile: the project's own if it has one, else a generated one"
                text.set(
                    extension.dockerfile
                        .map { it.asFile.readText() }
                        .orElse(identity().map { DockerfileTemplate.render(template, it) }),
                )
                outputFile.set(wiring.workDir.map { it.file("docker/Dockerfile") })
            }
        return project.tasks.register("appDockerContext", Sync::class.java) {
            group = wiring.taskGroup
            description = "Prepares a container build context from a Linux archive: Dockerfile + app/"
            from(dockerfile)
            into(project.layout.buildDirectory.dir("docker-context"))
        }
    }

    private fun identity() = extension.appName
        .zip(wiring.version) { name, version -> name to version }
        .zip(wiring.gitCommit) { (name, version), commit -> ImageIdentity(name, version, commit) }
}

/** What an image built from the generated Dockerfile says about itself. */
internal data class ImageIdentity(val name: String, val version: String, val revision: String)

/** The generated Dockerfile: the template with the app's name, release and commit filled in. */
internal object DockerfileTemplate {
    fun render(template: String, identity: ImageIdentity) = template
        .replace("@APP_NAME@", identity.name)
        .replace("@APP_VERSION@", identity.version)
        .replace("@GIT_COMMIT@", identity.revision)
}
