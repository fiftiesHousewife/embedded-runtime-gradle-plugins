package io.github.fiftieshousewife.nodeapp.tasks

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files

/**
 * Checks a production `node_modules` against the target, using npm's hidden lockfile.
 *
 * Packages with install scripts (node-gyp builds, prebuild downloads) produce host-specific
 * binaries, so they make a cross build wrong. On a host build they are reported when
 * `--ignore-scripts` skipped them, because the package may then be incomplete at runtime.
 */
@DisableCachingByDefault(because = "Verification only")
abstract class VerifyNodeModules : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nodeModules: DirectoryProperty

    @get:Input abstract val crossBuild: Property<Boolean>

    @get:Input abstract val ignoredScripts: Property<Boolean>

    @get:Input abstract val portable: ListProperty<String>

    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val lock = nodeModules.get().asFile.resolve(".package-lock.json")
        val lockfile = if (lock.isFile) JsonSlurper().parse(lock) as Map<*, *> else emptyMap<String, Any>()
        val withScripts = InstallScripts.packages(lockfile, portable.get())
        val report = if (withScripts.isEmpty()) "portable" else withScripts.joinToString("\n")
        Files.writeString(reportFile.get().asFile.toPath(), report + "\n")
        when {
            withScripts.isEmpty() -> Unit
            crossBuild.get() -> throw GradleException(InstallScripts.crossBuildRefusal(withScripts))
            ignoredScripts.get() -> logger.warn(InstallScripts.skippedWarning(withScripts))
        }
    }
}

/** Which installed packages run install scripts, and what that means for a build. */
internal object InstallScripts {
    /** Packages npm's hidden lockfile marks `hasInstallScript`, minus those vetted as [portable]. */
    fun packages(lockfile: Map<*, *>, portable: List<String>): List<String> = (lockfile["packages"] as? Map<*, *>)
        .orEmpty()
        .filterValues { (it as? Map<*, *>)?.get("hasInstallScript") == true }
        .keys
        .map { it.toString().substringAfterLast("node_modules/") }
        .filterNot { it in portable }
        .sorted()

    fun crossBuildRefusal(packages: List<String>) =
        "Cannot cross-build: these packages have install scripts that build or download host-specific binaries: " +
            "${packages.joinToString()}. Build this app on its target (CI)."

    fun skippedWarning(packages: List<String>) =
        "Install scripts were skipped (--ignore-scripts) for: ${packages.joinToString()}. If they are needed at " +
            "runtime, set nodeApp.ignoreInstallScripts = false after vetting them."
}
