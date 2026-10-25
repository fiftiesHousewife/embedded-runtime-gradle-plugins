package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppServices
import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Ecosystem
import io.github.fiftieshousewife.appcore.RuntimeOptions
import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.javaapp.tasks.JdepsModules
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.plugins.JavaApplication
import org.gradle.jvm.application.tasks.CreateStartScripts
import javax.inject.Inject

/**
 * `io.github.fiftieshousewife.java-app` — a Java application packaged with a jlink-trimmed runtime
 * of the configured JDK distribution into a self-contained `.tar.gz` per target.
 *
 * Compilation and the embedded runtime use the same feature release and the same exact pin: the
 * toolchain resolver ([JavaAppSettingsPlugin]) and the runtime archives both come from [JdkCatalog]
 * and [JdkSources].
 *
 * The archive layout follows the `application` plugin's: `bin/<app>`, `lib/` (the application jar
 * and its dependencies), plus `runtime/` (jlink image), `conf.defaults/` and
 * `META-INF/{app.json,sbom.cdx.json}`.
 *
 * The runtime's modules default to what `jdeps` finds plus `additionalModules`; `jlinkFindModules`
 * finds the ones jdeps cannot see by running the app.
 */
abstract class JavaAppPlugin
@Inject
constructor(
    private val archives: ArchiveOperations,
    private val components: SoftwareComponentFactory,
) : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("application")
        val extension = project.extensions.create(ECOSYSTEM.extensionName, JavaAppExtension::class.java)
        val wiring = AppWiring(project, ECOSYSTEM, extension, AppServices(archives, components))
        wiring.applyConventions()
        val detectedModules = project.objects.listProperty(String::class.java)
        JavaConventions(project, extension).apply(detectedModules)

        // The archive replaces the application plugin's script-based distributions; keeping both
        // would publish two different ways to run the same app. installDist remains available.
        listOf("distTar", "distZip").forEach { name -> project.tasks.named(name) { enabled = false } }
        useEmbeddedRuntimeScripts(project, extension)

        // Targets and the JDK release are final once the build script has run; the JDK tasks
        // are registered then, before the shared pipeline asks for each target's contribution.
        val runtimeTasks = JavaRuntimeTasks(project, extension, wiring)
        project.afterEvaluate {
            detectedModules.set(runtimeTasks.jdeps().flatMap { it.outputFile }.map { JdepsModules.read(it.asFile) })
            runtimeTasks.findModules()
        }
        val ecosystem = JavaEcosystem(project, extension)
        wiring.register { target -> ecosystem.spec(runtimeTasks.customise(target, runtimeTasks.jlink(target))) }
        project.tasks.named("appSmokeTest", AppSmokeTest::class.java) {
            failureHint.set("If the app is missing a JDK module, ./gradlew jlinkFindModules finds which.")
        }
    }

    /**
     * Configured when the task is created, after the build script has run, so a build's own
     * `tasks.startScripts { … }` still wins and `runtimeOptions` is final.
     */
    private fun useEmbeddedRuntimeScripts(project: Project, extension: JavaAppExtension) {
        val application = project.extensions.getByType(JavaApplication::class.java)
        project.tasks.named("startScripts", CreateStartScripts::class.java) {
            val locked = extension.runtimeOptions.get() == RuntimeOptions.LOCKED
            val defaults = runtimeDefaults(extension.runtimeDefaults)
            val projectOptions = application.applicationDefaultJvmArgs.toList() + extension.jvmArgs.get()
            unixStartScriptGenerator = EmbeddedRuntimeScriptGenerator(false, locked, defaults.logDirFiles)
            windowsStartScriptGenerator = EmbeddedRuntimeScriptGenerator(true, locked, defaults.logDirFiles)
            defaultJvmOpts = JavaRuntimeDefaults.fixedOptions(defaults, projectOptions) + projectOptions
        }
    }

    private fun runtimeDefaults(defaults: JavaRuntimeDefaults) = JavaRuntimeDefaults.Settings(
        enabled = defaults.enabled.get(),
        maxRamPercentage = defaults.maxRamPercentage.get(),
        exitOnOutOfMemory = defaults.exitOnOutOfMemory.get(),
        heapDumpOnOutOfMemory = defaults.heapDumpOnOutOfMemory.get(),
        garbageCollector = defaults.garbageCollector.orNull,
        disableAttach = defaults.disableAttach.get(),
    )

    companion object {
        val ECOSYSTEM =
            Ecosystem(
                id = "java",
                extensionName = "javaApp",
                settingsPluginId = JavaAppSettingsPlugin.ID,
            )
    }
}
