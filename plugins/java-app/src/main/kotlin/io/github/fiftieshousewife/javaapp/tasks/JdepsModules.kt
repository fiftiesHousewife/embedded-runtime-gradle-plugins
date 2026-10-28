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
import java.nio.file.Files
import javax.inject.Inject

/**
 * Runs `jdeps --print-module-deps` over the app jar and its runtime classpath, writing the JDK
 * modules they use as one comma-separated line, to which `javaApp.additionalModules` is added.
 *
 * jdeps sees only static references. Modules loaded by name at runtime (TLS providers, JNDI,
 * locale data) need `javaApp.additionalModules`; `jlinkFindModules` finds them.
 */
@CacheableTask
abstract class JdepsModules
@Inject
constructor(private val exec: ExecOperations) : DefaultTask() {
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
        val output = out.toString(Charsets.UTF_8).trim()
        if (result.exitValue != 0) throw GradleException("jdeps failed:\n$output")
        val modules = parse(output)
        Files.writeString(outputFile.get().asFile.toPath(), modules.joinToString(",") + "\n")
        logger.info("jdeps found: ${modules.joinToString()}")
    }

    companion object {
        /** The modules from jdeps' output: its last line, comma-separated; earlier lines are warnings. */
        internal fun parse(output: String) = output
            .lines()
            .last()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)

        fun read(file: File): List<String> = parse(Files.readString(file.toPath()).trim())
    }
}
