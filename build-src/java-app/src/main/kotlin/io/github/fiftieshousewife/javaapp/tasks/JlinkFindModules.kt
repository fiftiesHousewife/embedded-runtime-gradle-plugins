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
            val launcher = app.resolve(if (windows.get()) "bin/${appName.get()}.bat" else "bin/${appName.get()}")
            val classes by lazy { packagesToModules(home) }
            val start = modules.get()
            val linked = start.toCollection(LinkedHashSet())

            repeat(maxAttempts.get()) { attempt ->
                val runtime = app.resolve("runtime")
                fs.delete { delete(runtime) }
                JdkTools.link(exec, home, home, linked, options.get(), runtime)
                val work = temporaryDir.resolve("attempt-${attempt + 1}").apply { mkdirs() }
                when (val result = SmokeRun(launcher, windows.get(), work, healthTimeout.get()).run(expectedVersion.get())) {
                    SmokeRun.Result.Healthy -> {
                        return report(start, linked)
                    }

                    is SmokeRun.Result.Failed -> {
                        val missing =
                            missingModules(result.output, classes) - linked
                        if (missing.isEmpty()) {
                            throw GradleException(
                                "${appName.get()} ${result.reason}, and its output names no missing JDK class, so this " +
                                    "is not something a module can fix:\n${result.tail}",
                            )
                        }
                        logger.lifecycle("Attempt ${attempt + 1}: ${result.reason}; adding ${missing.joinToString()}")
                        linked += missing
                    }
                }
            }
            throw GradleException("${appName.get()} still failing after ${maxAttempts.get()} attempts with ${linked.joinToString()}")
        }

        private fun report(
            start: List<String>,
            linked: Set<String>,
        ) {
            val added = linked - start.toSet()
            val message =
                if (added.isEmpty()) {
                    "${appName.get()} is healthy with its current modules; nothing to add."
                } else {
                    "${appName.get()} is healthy once these modules are added:\n\n" +
                        "  javaApp { additionalModules.addAll(${added.joinToString { "\"$it\"" }}) }"
                }
            reportFile.get().asFile.writeText(message + "\n")
            logger.lifecycle(message)
        }

        /** Which module holds each package of [home]'s runtime image, read with `jimage list`. */
        private fun packagesToModules(home: File): Map<String, String> {
            val out = ByteArrayOutputStream()
            exec.exec {
                executable = JdkTools.tool(home, "jimage").absolutePath
                args("list", home.resolve("lib/modules").absolutePath)
                standardOutput = out
            }
            val map = HashMap<String, String>()
            var module: String? = null
            out.toString().lineSequence().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("Module: ") -> {
                        module = trimmed.removePrefix("Module: ")
                    }

                    trimmed.endsWith(".class") && module != null -> {
                        map.putIfAbsent(trimmed.substringBeforeLast('/', "").replace('/', '.'), module!!)
                    }
                }
            }
            return map
        }

        companion object {
            private val MISSING_CLASS = Regex("""(?:ClassNotFoundException|NoClassDefFoundError)[:\s]+([\w$./]+)""")

            /** Failures that name no class but always mean one module. */
            private val SYMPTOMS =
                mapOf(
                    "UnsupportedCharsetException" to "jdk.charsets",
                    "sun.util.resources.provider" to "jdk.localedata",
                )

            /** The JDK modules [output] shows to be missing, given which module holds each package. */
            fun missingModules(
                output: String,
                packages: Map<String, String>,
            ): Set<String> {
                val fromClasses =
                    MISSING_CLASS
                        .findAll(output)
                        .map { it.groupValues[1].replace('/', '.') }
                        .mapNotNull { packages[it.substringBeforeLast('.', "")] }
                val fromSymptoms = SYMPTOMS.filterKeys { it in output }.values
                return (fromClasses + fromSymptoms).toSet()
            }
        }
    }
