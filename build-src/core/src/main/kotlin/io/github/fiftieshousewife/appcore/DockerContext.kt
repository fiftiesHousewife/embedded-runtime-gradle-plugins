package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * `appDockerContext`: a container build context holding a Dockerfile (the project's own if it has
 * one, else a generated one) and every Linux archive extracted under `app/<cpu>/` (`amd64`, `arm64`).
 */
internal class DockerContext(
    private val project: Project,
    private val wiring: AppWiring,
    private val archives: ArchiveOperations,
) {
    private val extension = wiring.extension

    private val task: TaskProvider<Sync> = register()

    /**
     * Containers run Linux: every Linux archive goes under `app/<cpu>/`, named as container
     * platforms name CPUs (`amd64`, `arm64`), so one context builds an image for each.
     */
    fun useLinuxArchives(built: List<TargetTasks>) {
        val linux = built.filter { it.target.os == Os.LINUX }
        val archiveOps = archives
        task.configure {
            if (linux.isEmpty()) {
                val targets = "${wiring.ecosystem.extensionName}.targets"
                val message = "appDockerContext needs a Linux archive; add a linux target to $targets"
                doFirst { throw GradleException(message) }
                return@configure
            }
            linux.forEach { tasks ->
                // Not `into(...)`: inside a child spec the path already starts with it, so dropping the
                // first segment would drop that instead of the archive's `<app>-<version>/`.
                from(tasks.archive.flatMap { it.archiveFile }.map { archiveOps.archiveTree(it.asFile) }) {
                    AppCopySpecs.dropTopLevelDirectory(this, replacement = "app/${platformArch(tasks.target.arch)}")
                }
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
                        .map { OwnDockerfile.read(it.asFile) }
                        .orElse(identity().map { DockerfileTemplate.render(template, it) }),
                )
                outputFile.set(wiring.workDir.map { it.file("docker/Dockerfile") })
            }
        return project.tasks.register("appDockerContext", Sync::class.java) {
            group = wiring.taskGroup
            description = "Prepares a container build context: Dockerfile + every Linux archive under app/<cpu>/"
            from(dockerfile)
            into(project.layout.buildDirectory.dir("docker-context"))
        }
    }

    private fun identity() = extension.appName
        .zip(wiring.version) { name, version -> name to version }
        .zip(wiring.gitCommit) { (name, version), commit -> Triple(name, version, commit) }
        .zip(extension.license) { (name, version, commit), license ->
            ImageIdentity(name, version, commit, license)
        }
        .zip(wiring.gitCommitTime.orElse("")) { identity, time -> identity.copy(created = time.ifEmpty { null }) }
        .zip(extension.sourceUrl.orElse("")) { identity, url -> identity.copy(source = url.ifEmpty { null }) }

    companion object {
        /** A target's CPU as container platforms name it, which is what `TARGETARCH` holds. */
        fun platformArch(arch: Arch) = when (arch) {
            Arch.X64 -> "amd64"
            Arch.AARCH64 -> "arm64"
        }
    }
}

/**
 * What an image built from the generated Dockerfile says about itself. [created] is the commit's
 * time, not the build's, so the same commit builds the same image; it and [source] are left out
 * when unknown.
 */
internal data class ImageIdentity(
    val name: String,
    val version: String,
    val revision: String,
    val licenses: String,
    val created: String? = null,
    val source: String? = null,
)

/** The generated Dockerfile: the template with the app's name, release, commit and labels filled in. */
internal object DockerfileTemplate {
    private const val PREFIX = "org.opencontainers.image"

    fun render(template: String, identity: ImageIdentity): String {
        val labels =
            listOfNotNull(
                "title" to identity.name,
                "version" to identity.version,
                "revision" to identity.revision,
                identity.created?.let { "created" to it },
                identity.source?.let { "source" to it },
                "licenses" to identity.licenses,
            ).map { (key, value) -> "$PREFIX.$key=\"${escape(value)}\"" } +
                // Expanded by the builder, so it names the base image actually used, digest included.
                listOf("$PREFIX.base.name=\"${'$'}{BASE_IMAGE}\"")
        return template
            .replace("@LABELS@", labels.joinToString(" \\\n      "))
            .replace("@APP_NAME@", identity.name)
            .replace("@BASE_IMAGE@", "${PluginVersion.dockerBaseImage}@${PluginVersion.dockerBaseDigest}")
    }

    /** A Dockerfile double-quoted value: `\`, `"` and `$` are taken literally only when escaped. */
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}

/**
 * A project's own Dockerfile. The context holds one archive per CPU, under `app/amd64/` and
 * `app/arm64/`, so a Dockerfile that copies all of `app/` (as one written for 0.0.2 and earlier
 * does) builds an image with no `/app/bin` that fails only when it starts; it is refused here.
 */
internal object OwnDockerfile {
    private val copiesWholeApp = Regex("""(?m)^\s*(COPY|ADD)\b.*\sapp/?\s""")

    fun read(file: File): String = file.readText().also { text ->
        if (copiesWholeApp.containsMatchIn(text)) {
            throw GradleException(
                "$file copies app/, which now holds one archive per CPU (app/amd64/, app/arm64/). Copy the one " +
                    "being built:\n  ARG TARGETARCH\n  COPY app/${'$'}{TARGETARCH}/ /app/",
            )
        }
    }
}
