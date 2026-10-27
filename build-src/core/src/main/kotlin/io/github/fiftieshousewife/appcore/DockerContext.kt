package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.UpdateDockerfile
import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * `appDockerContext`: a container build context holding the project's Dockerfile, which
 * `appDockerfile` creates and keeps up to date ([ManagedDockerfile]), and every Linux archive
 * extracted under `app/<cpu>/` (`amd64`, `arm64`).
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
                    AppCopySpecs.dropTopLevelDirectory(this, replacement = "app/${tasks.target.arch.container}")
                }
            }
        }
    }

    private fun register(): TaskProvider<Sync> {
        val template =
            DockerContext::class.java.getResource("/io/github/fiftieshousewife/appcore/docker/Dockerfile")?.readText()
                ?: error("Dockerfile template missing from the app-core jar")
        val dockerfile =
            project.tasks.register("appDockerfile", UpdateDockerfile::class.java) {
                group = wiring.taskGroup
                description =
                    "Creates the project's Dockerfile if missing, and keeps its embedded-runtime block up to date"
                managedBlock.set(identity().map { DockerfileTemplate.render(template, it) })
                this.dockerfile.set(extension.dockerfile)
            }
        // The context's copy: the project's Dockerfile with this build's release, commit and time.
        val forContext =
            project.tasks.register("appDockerContextFile", WriteTextFile::class.java) {
                description = "Writes the Dockerfile for the container build context, with this build's release"
                text.set(
                    dockerfile.flatMap { it.dockerfile }.map { OwnDockerfile.read(it.asFile) }
                        .zip(buildArguments(), ContextDockerfile::render),
                )
                outputFile.set(wiring.workDir.map { it.file("docker/Dockerfile") })
            }
        return project.tasks.register("appDockerContext", Sync::class.java) {
            group = wiring.taskGroup
            description = "Prepares a container build context: Dockerfile + every Linux archive under app/<cpu>/"
            from(forContext)
            into(project.layout.buildDirectory.dir("docker-context"))
        }
    }

    /** What the image says about itself and doesn't change from build to build. */
    private fun identity() = extension.appName
        .zip(extension.license) { name, license -> ImageIdentity(name, license) }
        .zip(extension.sourceUrl.orElse("")) { identity, url -> identity.copy(source = url.ifEmpty { null }) }

    /** The per-build values for the Dockerfile's `ARG`s: the release, its commit and that commit's time. */
    private fun buildArguments() = wiring.version
        .zip(wiring.gitCommit) { version, commit -> mapOf("APP_VERSION" to version, "GIT_COMMIT" to commit) }
        .zip(wiring.gitCommitTime.orElse("")) { values, time ->
            if (time.isEmpty()) values else values + ("CREATED" to time)
        }
}

/**
 * What an image built from the generated Dockerfile says about itself, beside the per-build release,
 * commit and time it takes as `ARG`s; [source] is left out when unknown.
 */
internal data class ImageIdentity(val name: String, val licenses: String, val source: String? = null)

/** The plugins' block of the Dockerfile: the template with the app's name, labels and base image filled in. */
internal object DockerfileTemplate {
    private const val PREFIX = "org.opencontainers.image"

    fun render(template: String, identity: ImageIdentity): String {
        val labels =
            listOfNotNull(
                "title" to identity.name,
                identity.source?.let { "source" to it },
                "licenses" to identity.licenses,
            ).map { (key, value) -> "$PREFIX.$key=\"${escape(value)}\"" } +
                // Expanded by the builder: this build's release, commit and time, and the base image used.
                listOf(
                    "version" to "APP_VERSION",
                    "revision" to "GIT_COMMIT",
                    "created" to "CREATED",
                    "base.name" to "BASE_IMAGE",
                )
                    .map { (key, argument) -> "$PREFIX.$key=\"${'$'}{$argument}\"" }
        return template
            .replace("@LABELS@", labels.joinToString(" \\\n      "))
            .replace("@APP_NAME@", identity.name)
            .replace("@BASE_IMAGE@", "${PluginVersion.dockerBaseImage}@${PluginVersion.dockerBaseDigest}")
    }

    /** A Dockerfile double-quoted value: `\`, `"` and `$` are taken literally only when escaped. */
    fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}

/**
 * The block of the project's Dockerfile the plugins own, between two marker lines. The plugins
 * create the file with just that block, and later rewrite only the block, so anything written
 * outside it stays. A Dockerfile without the markers is the project's alone.
 */
internal object ManagedDockerfile {
    private const val START = "# >>> embedded-runtime"
    private const val END = "# <<< embedded-runtime"

    fun block(content: String) =
        "$START: written by the io.github.fiftieshousewife java-app / node-app plugin, which keeps\n" +
            "#     everything up to the closing line. Add your own instructions after it.\n" +
            content.trimEnd() + "\n$END\n"

    /**
     * [existing] with the plugins' block made [content]: a new file holding just the block when
     * there is none, or null when the file has no block, being the project's own.
     */
    fun update(existing: String?, content: String): String? {
        existing ?: return block(content)
        val lines = existing.lines()
        val start = lines.indexOfFirst { it.startsWith(START) }
        val end = lines.indexOfFirst { it.startsWith(END) }
        if (start < 0 || end < start) return null
        val before = if (start == 0) "" else lines.take(start).joinToString("\n") + "\n"
        val after = lines.drop(end + 1).joinToString("\n")
        return before + block(content) + after
    }
}

/** The Dockerfile as it goes in the build context: its per-build `ARG`s given this build's values. */
internal object ContextDockerfile {
    private val BARE_ARG = Regex("""^ARG\s+(\w+)\s*$""")

    fun render(dockerfile: String, values: Map<String, String>): String =
        dockerfile.split("\n").joinToString("\n") { line ->
            val name = BARE_ARG.matchEntire(line.trim())?.groupValues?.get(1)
            values[name]?.let { "ARG $name=\"${DockerfileTemplate.escape(it)}\"" } ?: line
        }
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
