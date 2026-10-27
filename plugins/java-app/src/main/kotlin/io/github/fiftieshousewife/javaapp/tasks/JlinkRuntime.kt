package io.github.fiftieshousewife.javaapp.tasks

import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Runs the host's jlink against the target JDK's `jmods`, so a cross link needs no container. The JDKs are
 * `@Internal` since the pin they come from is the tracked input: Gradle need not hash ~300 MB of JDK every build.
 */
@CacheableTask
abstract class JlinkRuntime
@Inject
constructor(
    private val exec: ExecOperations,
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:Internal abstract val toolJdk: DirectoryProperty

    @get:Internal abstract val targetJdk: DirectoryProperty

    @get:Internal abstract val targetJmods: DirectoryProperty

    @get:Input abstract val jdkVersion: Property<String>

    @get:Input abstract val host: Property<Target>

    @get:Input abstract val target: Property<Target>

    @get:Input abstract val modules: ListProperty<String>

    @get:Input abstract val options: ListProperty<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun link() {
        val out = outputDir.get().asFile
        fs.delete { delete(out) } // jlink refuses to write into an existing directory

        val modules = modules.get()
        if (modules.isEmpty()) throw GradleException("javaApp.jlinkModules is empty; add at least java.base")

        val tool = JdkTools.home(toolJdk.get().asFile)
        val targetHome = JdkTools.home(targetJdk.get().asFile)
        JdkTools.requirePinned(jdkVersion.get(), targetHome)
        val cross = host.get() != target.get()
        val jmods = targetJmods.orNull?.asFile ?: targetHome.resolve("jmods")
        if (cross && !jmods.isDirectory) {
            throw GradleException(
                "Cross-linking for ${target.get()} needs the target JDK's jmods/ directory, which $targetHome lacks " +
                    "(JEP 493 builds). Use a JDK distribution that ships jmods, or build on ${target.get()}.",
            )
        }
        JdkTools.link(exec, JlinkRequest(tool, targetHome, modules, options.get(), out, jmods))
    }
}
