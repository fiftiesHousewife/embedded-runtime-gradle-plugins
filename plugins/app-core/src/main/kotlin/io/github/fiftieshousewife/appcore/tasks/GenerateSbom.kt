package io.github.fiftieshousewife.appcore.tasks

import groovy.json.JsonSlurper
import org.cyclonedx.model.Component
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/**
 * The runtime is listed as a `platform` component: scanners can't see a bundled JRE or Node binary through the
 * OS package database. Node packages come from npm's hidden lockfile, which lists exactly what was installed.
 */
@CacheableTask
abstract class GenerateSbom : DefaultTask() {
    @get:Input abstract val appGroup: Property<String>

    @get:Input abstract val appName: Property<String>

    @get:Input abstract val version: Property<String>

    @get:Input abstract val target: Property<String>

    @get:Input abstract val runtimeName: Property<String>

    @get:Input abstract val runtimeVersion: Property<String>

    /** `group:module:version:fileName` for each resolved Maven artifact. */
    @get:Input abstract val mavenComponents: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val mavenFiles: ConfigurableFileCollection

    @get:InputDirectory @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nodeModules: DirectoryProperty

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val app = CycloneDx.App(CycloneDx.Coordinates(appGroup.get(), appName.get(), version.get()), target.get())
        val filesByName = mavenFiles.files.associateBy { it.name }
        val libraries = mavenComponents.get().map { CycloneDx.mavenLibrary(it, filesByName) }
        val components = listOf(CycloneDx.runtime(runtimeName.get(), runtimeVersion.get())) + libraries + npmPackages()
        Files.writeString(outputFile.get().asFile.toPath(), CycloneDx.json(app, components) + "\n")
    }

    private fun npmPackages(): List<Component> {
        val lock = nodeModules.orNull?.asFile?.resolve(".package-lock.json")?.takeIf { it.isFile } ?: return emptyList()
        return NpmLockfile.npmPackages(JsonSlurper().parse(lock) as Map<*, *>)
    }
}
