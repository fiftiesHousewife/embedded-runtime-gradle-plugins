package io.github.fiftieshousewife.javaapp.tasks

import io.github.fiftieshousewife.appcore.tasks.SmokeRun
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Duration
import javax.inject.Inject

/**
 * Finds the JDK modules an app needs by running it: links a runtime with the current modules,
 * starts the app as the smoke test does, and when it fails because a JDK class is missing, adds the
 * module that holds that class and tries again, until the app is healthy.
 *
 * Reports the modules to add to `javaApp.additionalModules`. It works from what the app prints, so
 * it finds what fails at startup or on the health check, not code paths those never reach.
 */
@DisableCachingByDefault(because = "Executes the application")
abstract class JlinkFindModules
@Inject
constructor(
    private val exec: ExecOperations,
    private val fs: FileSystemOperations,
) : DefaultTask() {
    /** The full JDK for this machine: the jlink tool, the jmods, and the map of classes to modules. */
    @get:Internal abstract val jdk: DirectoryProperty

    /** This machine's assembled app; its `runtime/` is replaced on every attempt. */
    @get:Internal abstract val appDir: DirectoryProperty

    @get:Internal abstract val appName: Property<String>

    @get:Internal abstract val expectedVersion: Property<String>

    @get:Internal abstract val windows: Property<Boolean>

    @get:Internal abstract val modules: ListProperty<String>

    @get:Internal abstract val options: ListProperty<String>

    @get:Internal abstract val healthTimeout: Property<Duration>

    @get:Internal abstract val maxAttempts: Property<Int>

    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun find() {
        val home = JdkTools.home(jdk.get().asFile)
        val app = temporaryDir.resolve("app")
        fs.sync {
            from(appDir)
            into(app)
            exclude("runtime/**")
        }
        val classes by lazy { ModuleSearch.packagesToModules(jimageListing(home)) }
        val start = modules.get()
        val linked = search(1, start.toSet(), Search(home, app) { classes })
        val message = ModuleSearch.report(appName.get(), linked - start.toSet())
        Files.writeString(reportFile.get().asFile.toPath(), message + "\n")
        logger.lifecycle(message)
    }

    /** What every attempt shares: the JDK to link from, the app to run, and the class-to-module map. */
    private class Search(val home: File, val app: File, val classes: () -> Map<String, String>)

    /** Links [linked], starts the app, and on a missing JDK class tries again with its module added. */
    private tailrec fun search(attempt: Int, linked: Set<String>, search: Search): Set<String> {
        if (attempt > maxAttempts.get()) {
            throw GradleException(
                "${appName.get()} still failing after ${maxAttempts.get()} attempts with ${linked.joinToString()}",
            )
        }
        val result = run(attempt, linked, search)
        if (result !is SmokeRun.Result.Failed) return linked
        val missing = ModuleSearch.missingModules(result.output, search.classes()) - linked
        if (missing.isEmpty()) {
            throw GradleException(
                "${appName.get()} ${result.reason}, and its output names no missing JDK class, so this is not " +
                    "something a module can fix:\n${result.tail}",
            )
        }
        logger.lifecycle("Attempt $attempt: ${result.reason}; adding ${missing.joinToString()}")
        return search(attempt + 1, linked + missing, search)
    }

    private fun run(attempt: Int, linked: Set<String>, search: Search): SmokeRun.Result {
        val runtime = search.app.resolve("runtime")
        fs.delete { delete(runtime) }
        JdkTools.link(exec, JlinkRequest(search.home, search.home, linked, options.get(), runtime))
        val launcher = search.app.resolve(if (windows.get()) "bin/${appName.get()}.bat" else "bin/${appName.get()}")
        val work = temporaryDir.resolve("attempt-$attempt").apply { mkdirs() }
        return SmokeRun(launcher, windows.get(), work, healthTimeout.get()).run(expectedVersion.get())
    }

    /** `jimage list` of [home]'s runtime image: every class, grouped under its module. */
    private fun jimageListing(home: File): String {
        val out = ByteArrayOutputStream()
        exec.exec {
            executable = JdkTools.tool(home, "jimage").absolutePath
            args("list", home.resolve("lib/modules").absolutePath)
            standardOutput = out
        }
        return out.toString(Charsets.UTF_8)
    }
}
