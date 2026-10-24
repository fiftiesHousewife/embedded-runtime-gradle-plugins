package io.github.fiftieshousewife.platform.bundle.tasks

import io.github.fiftieshousewife.platform.runtime.Platform
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
import java.io.File
import javax.inject.Inject

/**
 * Links the trimmed Java runtime that ships inside the bundle, for [targetPlatform].
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

        @get:Input abstract val hostPlatform: Property<Platform>

        @get:Input abstract val targetPlatform: Property<Platform>

        @get:Input abstract val modules: ListProperty<String>

        @get:Input abstract val options: ListProperty<String>

        @get:OutputDirectory abstract val outputDir: DirectoryProperty

        @TaskAction
        fun link() {
            val out = outputDir.get().asFile
            fs.delete { delete(out) } // jlink refuses to write into an existing directory

            val modules = modules.get()
            if (modules.isEmpty()) throw GradleException("bundle.jlinkModules is empty; add at least java.base")

            val tool = jdkHome(toolJdk.get().asFile)
            val target = jdkHome(targetJdk.get().asFile)
            val cross = hostPlatform.get() != targetPlatform.get()
            val jmods = target.resolve("jmods")
            if (cross && !jmods.isDirectory) {
                throw GradleException(
                    "Cross-linking for ${targetPlatform.get()} needs the target JDK's jmods/ directory, which " +
                        "$target lacks (JEP 493 builds). Use a JDK distribution that ships jmods, or build on ${targetPlatform.get()}.",
                )
            }

            val jlink = tool.resolve(if (hostPlatform.get().isWindows) "bin/jlink.exe" else "bin/jlink")
            exec.exec {
                executable = jlink.absolutePath
                if (jmods.isDirectory) args("--module-path", jmods.absolutePath)
                args("--add-modules", modules.joinToString(","))
                args(options.get())
                args("--output", out.absolutePath)
            }
        }

        companion object {
            /** Finds the JDK home in an extracted archive (macOS archives nest it under `Contents/Home`). */
            fun jdkHome(extracted: File): File =
                listOf(extracted, extracted.resolve("Contents/Home")).firstOrNull { it.resolve("release").isFile }
                    ?: throw GradleException("No JDK found in $extracted (expected a 'release' file)")
        }
    }
