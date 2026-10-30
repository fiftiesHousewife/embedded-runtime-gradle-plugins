package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.DockerfileVariants
import io.github.fiftieshousewife.appcore.tasks.UpdateDockerfile
import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.CopySpec
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/**
 * `appDockerContext`: the project's Dockerfiles, filled in with this build, beside every Linux target's
 * runtime and app archives, unpacked as `runtime/<cpu>/` and `app/<cpu>/`, so an image keeps the runtime in
 * a layer of its own that a new release of the app leaves alone.
 */
internal class DockerContext(
    private val project: Project,
    private val wiring: AppWiring,
    private val archives: ArchiveOperations,
) {
    private val extension = wiring.extension

    /** `Dockerfile`, then each `Dockerfile.<variant>` at the project's root. */
    private val variants: List<ImageVariant> = listOf(ImageVariant(null)) +
        project.providers.of(DockerfileVariants::class.java) {
            parameters.directory.set(project.layout.projectDirectory)
        }.get().map(::ImageVariant)

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
                val runtime = tasks.runtimeArchive.flatMap { it.archiveFile }.map { archiveOps.archiveTree(it.asFile) }
                val app = tasks.archive.flatMap { it.archiveFile }.map { archiveOps.archiveTree(it.asFile) }
                val cpu = tasks.target.arch.container
                from(runtime) { layer(this, into = "$RUNTIME/$cpu") }
                from(app) { layer(this, into = "app/$cpu") }
                from(runtime) { layer(this, into = "$LAYERS/$cpu/$RUNTIME/app/$RUNTIME") }
                from(app) { layer(this, into = "$LAYERS/$cpu/app/app") }
            }
            doLast { ContextTimes.fix(destinationDir.toPath()) }
        }
    }

    /**
     * The archive's tree without its top-level directory, under [into]. Not `into(...)`: inside a child spec
     * the path already starts with it, so dropping a segment would drop that.
     */
    private fun layer(spec: CopySpec, into: String) {
        spec.eachFile {
            val segments = listOf(into) + relativePath.segments.drop(1)
            relativePath = RelativePath.parse(!isDirectory, segments.joinToString("/"))
        }
        spec.includeEmptyDirs = false
    }

    private fun register(): TaskProvider<Sync> {
        val template =
            DockerContext::class.java.getResource("/io/github/fiftieshousewife/appcore/docker/Dockerfile")?.readText()
                ?: error("Dockerfile template missing from the app-core jar")
        val managed =
            project.tasks.register("appDockerfile", UpdateDockerfile::class.java) {
                group = wiring.taskGroup
                description =
                    "Creates the project's Dockerfile if missing, and keeps its embedded-runtime block up to date"
                managedBlock.set(identity().map { DockerfileTemplate.render(template, it) })
                dockerfile.set(project.layout.projectDirectory.file(ImageVariant.DOCKERFILE))
            }
        val forContext = variants.map { contextFile(it, managed) }
        return project.tasks.register("appDockerContext", Sync::class.java) {
            group = wiring.taskGroup
            description = "Prepares a container build context: the Dockerfiles and every Linux archive, by CPU"
            from(forContext)
            into(project.layout.buildDirectory.dir("docker-context"))
        }
    }

    private fun contextFile(variant: ImageVariant, managed: TaskProvider<UpdateDockerfile>) =
        project.tasks.register("appDockerContextFile${variant.taskSuffix}", WriteTextFile::class.java) {
            description = "Writes ${variant.dockerfile} for the container build context, with this build's release"
            val source = if (variant.name == null) {
                managed.flatMap { it.dockerfile }.map { it.asFile }
            } else {
                project.provider { project.layout.projectDirectory.file(variant.dockerfile).asFile }
            }
            text.set(source.map(OwnDockerfile::read).zip(buildArguments(), ContextDockerfile::render))
            outputFile.set(wiring.workDir.map { it.file("docker/${variant.dockerfile}") })
        }

    private fun identity() = extension.appName
        .zip(extension.license) { name, license -> ImageIdentity(name, license) }
        .zip(wiring.facts.sourceUrl.orElse("")) { identity, url -> identity.copy(source = url.ifEmpty { null }) }

    private fun buildArguments() = wiring.version
        .zip(wiring.gitCommit) { version, commit -> mapOf("APP_VERSION" to version, "GIT_COMMIT" to commit) }
        .zip(wiring.facts.gitCommitTime.orElse("")) { values, time ->
            if (time.isEmpty()) values else values + ("CREATED" to time)
        }

    private companion object {
        const val RUNTIME = RuntimeArchive.DIRECTORY

        /** Each image layer's files under the path they take in the image, copied to `/` by one `COPY`. */
        const val LAYERS = "layers"
    }
}
