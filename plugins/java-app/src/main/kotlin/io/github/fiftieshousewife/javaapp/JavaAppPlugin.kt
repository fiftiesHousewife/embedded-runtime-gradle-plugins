package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppServices
import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Ecosystem
import io.github.fiftieshousewife.appcore.EmbeddedRuntime
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
 * A Java application packaged with a jlink-trimmed runtime, one archive per target. Compiler and runtime share one
 * pin, since the toolchain resolver and the runtime archives both come from [JdkCatalog] and [JdkSources].
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
        val conventions = JavaConventions(project, extension).also { it.apply(detectedModules) }

        // Keeping the application plugin's distributions would publish two different ways to run the same app.
        listOf("distTar", "distZip").forEach { name -> project.tasks.named(name) { enabled = false } }
        useEmbeddedRuntimeScripts(project, extension)

        // Targets and the JDK release are final only once the build script has run.
        val runtimeTasks = JavaRuntimeTasks(project, extension, wiring)
        project.afterEvaluate {
            detectedModules.set(runtimeTasks.jdeps().flatMap { it.outputFile }.map { JdepsModules.read(it.asFile) })
            runtimeTasks.findModules()
        }
        val distribution = conventions.sources.distribution
        wiring.registerRuntimeSupportCheck(
            extension.javaVersion.zip(extension.jdkVersion) { feature, version ->
                EmbeddedRuntime(distribution.supportProduct, feature.toString(), version)
            },
        )
        val ecosystem = JavaEcosystem(project, extension)
        wiring.register { target -> ecosystem.spec(runtimeTasks.customise(target, runtimeTasks.jlink(target))) }
        project.tasks.named("appSmokeTest", AppSmokeTest::class.java) {
            failureHint.set("If the app is missing a JDK module, ./gradlew jlinkFindModules finds which.")
        }
    }

    /** Configured on creation, so a build's own `startScripts { … }` wins and `runtimeOptions` is final. */
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
