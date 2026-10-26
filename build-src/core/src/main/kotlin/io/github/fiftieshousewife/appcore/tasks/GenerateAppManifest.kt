package io.github.fiftieshousewife.appcore.tasks

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Writes `META-INF/app.json`, printed by `bin/<app> version` and read by the installer and the
 * smoke test. No timestamp is recorded, so rebuilding the same commit produces the same archive.
 */
@CacheableTask
abstract class GenerateAppManifest : DefaultTask() {
    @get:Input abstract val appGroup: Property<String>

    @get:Input abstract val appName: Property<String>

    @get:Input abstract val version: Property<String>

    @get:Input abstract val target: Property<String>

    @get:Input abstract val ecosystem: Property<String>

    @get:Input abstract val runtimeName: Property<String>

    @get:Input abstract val runtimeVersion: Property<String>

    @get:Input @get:Optional
    abstract val gitCommit: Property<String>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val manifest =
            linkedMapOf(
                "manifestVersion" to 1,
                "group" to appGroup.get(),
                "name" to appName.get(),
                "version" to version.get(),
                "target" to target.get(),
                "ecosystem" to ecosystem.get(),
                "runtime" to linkedMapOf("name" to runtimeName.get(), "version" to runtimeVersion.get()),
                "gitCommit" to (gitCommit.orNull ?: "unknown"),
            )
        outputFile.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
    }
}

/**
 * The output of one read-only git command (`git rev-parse HEAD`, say), or absent outside a git
 * checkout or without git on the PATH. Re-evaluated whenever the output would change.
 */
abstract class GitValueSource : ValueSource<String, GitValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val workingDirectory: DirectoryProperty

        /** The arguments after `git`. */
        val arguments: ListProperty<String>
    }

    @get:Inject abstract val exec: ExecOperations

    override fun obtain(): String? {
        val out = ByteArrayOutputStream()
        // No git on PATH fails to start at all, which means "unknown" just as a non-zero exit does.
        val result =
            runCatching {
                exec.exec {
                    commandLine(listOf("git") + parameters.arguments.get())
                    workingDir(parameters.workingDirectory.get().asFile)
                    standardOutput = out
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            }.getOrNull()
        return out.toString(Charsets.UTF_8).trim().takeIf { result?.exitValue == 0 && it.isNotEmpty() }
    }
}
