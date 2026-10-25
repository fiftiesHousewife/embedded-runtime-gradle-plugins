package io.github.fiftieshousewife.javaapp.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Runs `jdeps --print-module-deps` over the app jar and its runtime classpath, writing the JDK
 * modules they use as one comma-separated line: the default for `javaApp.jlinkModules`.
 *
 * jdeps sees only static references. Modules loaded by name at runtime (TLS providers, JNDI,
 * locale data) need `javaApp.additionalModules`; `jlinkFindModules` finds them.
 */
@CacheableTask
abstract class JdepsModules
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** Derived from [jdkVersion], which is the tracked input. */
        @get:Internal abstract val jdk: DirectoryProperty

        @get:Input abstract val jdkVersion: Property<String>

        /** The release whose classes a multi-release jar is read for. */
        @get:Input abstract val javaFeature: Property<Int>

        @get:Classpath abstract val appJar: ConfigurableFileCollection

        @get:Classpath abstract val classpath: ConfigurableFileCollection

        @get:OutputFile abstract val outputFile: RegularFileProperty

        @TaskAction
        fun run() {
            val out = ByteArrayOutputStream()
            val result =
                exec.exec {
                    executable = JdkTools.tool(JdkTools.home(jdk.get().asFile), "jdeps").absolutePath
                    args("--print-module-deps", "--ignore-missing-deps", "--recursive")
                    args("--multi-release", javaFeature.get().toString())
                    if (!classpath.isEmpty) args("--class-path", classpath.files.joinToString(File.pathSeparator))
                    args(appJar.files.map { it.absolutePath })
                    standardOutput = out
                    errorOutput = out
                    isIgnoreExitValue = true
                }
            val output = out.toString().trim()
            if (result.exitValue != 0) throw GradleException("jdeps failed:\n$output")
            val modules =
                output
                    .lines()
                    .last()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            outputFile.get().asFile.writeText(modules.joinToString(",") + "\n")
            logger.info("jdeps found: ${modules.joinToString()}")
        }

        companion object {
            fun read(file: File): List<String> =
                file
                    .readText()
                    .trim()
                    .split(',')
                    .filter(String::isNotEmpty)
        }
    }
