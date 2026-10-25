package io.github.fiftieshousewife.javaapp.tasks

import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties

/** Locating a JDK and running its tools; shared by `jlink`, `jdeps` and `jlinkFindModules`. */
object JdkTools {
    /** The JDK home in an extracted archive or installation (macOS nests it under `Contents/Home`). */
    fun home(dir: File): File =
        listOf(dir, dir.resolve("Contents/Home")).firstOrNull { it.resolve("release").isFile }
            ?: throw GradleException("No JDK found in $dir (expected a 'release' file)")

    /** `JAVA_VERSION` from the home's `release` file, e.g. `21.0.13`. */
    fun javaVersion(home: File): String? =
        Properties()
            .apply { home.resolve("release").inputStream().use(::load) }
            .getProperty("JAVA_VERSION")
            ?.trim('"')

    fun tool(
        home: File,
        name: String,
    ): File = home.resolve("bin/$name").let { if (it.isFile) it else home.resolve("bin/$name.exe") }

    /**
     * Links [modules] from [targetHome] into [output] with the jlink of [toolHome]. The two must be
     * the same exact release: jlink refuses jmods from any other.
     */
    @Suppress("LongParameterList") // One jlink invocation: every argument is a jlink input.
    fun link(
        exec: ExecOperations,
        toolHome: File,
        targetHome: File,
        modules: Collection<String>,
        options: List<String>,
        output: File,
    ) {
        val toolVersion = javaVersion(toolHome)
        val targetVersion = javaVersion(targetHome)
        if (toolVersion != targetVersion) {
            throw GradleException(
                "jlink needs the same JDK release on both sides: $toolHome is $toolVersion but $targetHome is " +
                    "$targetVersion. Point both at the same version.",
            )
        }
        val jmods = targetHome.resolve("jmods")
        exec.exec {
            executable = tool(toolHome, "jlink").absolutePath
            if (jmods.isDirectory) args("--module-path", jmods.absolutePath)
            args("--add-modules", modules.joinToString(","))
            args(options)
            args("--output", output.absolutePath)
        }
    }
}
