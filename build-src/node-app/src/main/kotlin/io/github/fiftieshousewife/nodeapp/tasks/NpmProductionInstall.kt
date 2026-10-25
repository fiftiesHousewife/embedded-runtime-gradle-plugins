package io.github.fiftieshousewife.nodeapp.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.nio.file.Files
import javax.inject.Inject

/**
 * Production `npm ci --omit=dev` into an isolated directory, so packaging never sees development
 * dependencies or the developer's own `node_modules`.
 */
@CacheableTask
abstract class NpmProductionInstall
@Inject
constructor(
    private val exec: ExecOperations,
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:Internal abstract val nodeHome: DirectoryProperty

    @get:Input abstract val nodeVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packageJson: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packageLock: RegularFileProperty

    @get:Input abstract val npmArgs: ListProperty<String>

    @get:Internal abstract val npmrc: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun install() {
        val dir = outputDir.get().asFile
        fs.delete { delete(dir) }
        Files.createDirectories(dir.toPath())
        Files.copy(packageJson.get().asFile.toPath(), dir.toPath().resolve("package.json"))
        Files.copy(packageLock.get().asFile.toPath(), dir.toPath().resolve("package-lock.json"))
        val tools = NodeLayout.locate(nodeHome.get().asFile)
        exec.exec {
            workingDir(dir)
            NodeLayout.npm(this, tools, listOf("ci", "--omit=dev") + npmArgs.get(), npmrc.orNull?.asFile)
        }
        // An application without dependencies gets no node_modules from npm; downstream tasks
        // (SBOM, verification, assembly) treat it as an empty but present directory.
        Files.createDirectories(dir.toPath().resolve("node_modules"))
    }
}
