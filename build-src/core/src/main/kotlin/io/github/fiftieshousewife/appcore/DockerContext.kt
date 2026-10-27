package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.UpdateDockerfile
import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import java.io.File

internal class DockerContext(
    private val project: Project,
    private val wiring: AppWiring,
    private val archives: ArchiveOperations,
) {
    private val extension = wiring.extension

    private val task: TaskProvider<Sync> = register()

    /** Named as container platforms name CPUs (`amd64`, `arm64`), so one context builds an image for each. */
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

    private fun identity() = extension.appName
        .zip(extension.license) { name, license -> ImageIdentity(name, license) }
        .zip(extension.sourceUrl.orElse("")) { identity, url -> identity.copy(source = url.ifEmpty { null }) }

    private fun buildArguments() = wiring.version
        .zip(wiring.gitCommit) { version, commit -> mapOf("APP_VERSION" to version, "GIT_COMMIT" to commit) }
        .zip(wiring.gitCommitTime.orElse("")) { values, time ->
            if (time.isEmpty()) values else values + ("CREATED" to time)
        }
}

internal data class ImageIdentity(val name: String, val licenses: String, val source: String? = null)

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

/** Only the block between the markers is rewritten, so anything outside it stays; without them the file is left be. */
internal object ManagedDockerfile {
    private const val START = "# >>> embedded-runtime"
    private const val END = "# <<< embedded-runtime"

    fun block(content: String) =
        "$START: written by the io.github.fiftieshousewife java-app / node-app plugin, which keeps\n" +
            "#     everything up to the closing line. Add your own instructions after it.\n" +
            content.trimEnd() + "\n$END\n"

    /** Null when the file has no block, being the project's own. */
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

internal object ContextDockerfile {
    private val BARE_ARG = Regex("""^ARG\s+(\w+)\s*$""")

    fun render(dockerfile: String, values: Map<String, String>): String =
        dockerfile.split("\n").joinToString("\n") { line ->
            val name = BARE_ARG.matchEntire(line.trim())?.groupValues?.get(1)
            values[name]?.let { "ARG $name=\"${DockerfileTemplate.escape(it)}\"" } ?: line
        }
}

/**
 * Refuses a Dockerfile that copies all of `app/` (as one written for 0.0.2 and earlier does): with one archive
 * per CPU it builds an image with no `/app/bin` that fails only when it starts.
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
