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
 * Links the trimmed Java runtime that ships inside the archive, for [target].
 *
 * `jlink` always runs from the build host's JDK ([toolJdk]) and links the target JDK's `jmods`
 * ([targetJdk]). When target and host differ this is a cross link, which is how a Windows desktop
 * without WSL2 or containers can still produce the linux-x64 runtime. Both JDKs must be the same
 * exact version, which the plugin guarantees by resolving both from one pin.
 *
 * The JDK directories are `@Internal`: they are derived from the exact pinned version, which is
 * the tracked input, so Gradle need not hash ~300 MB of JDK on every build.
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
        val cross = host.get() != target.get()
        if (cross && !targetHome.resolve("jmods").isDirectory) {
            throw GradleException(
                "Cross-linking for ${target.get()} needs the target JDK's jmods/ directory, which $targetHome lacks " +
                    "(JEP 493 builds). Use a JDK distribution that ships jmods, or build on ${target.get()}.",
            )
        }
        JdkTools.link(exec, JlinkRequest(tool, targetHome, modules, options.get(), out))
    }
}
