package io.github.fiftieshousewife.javaapp.tasks

import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.Files
import java.util.Properties

/** Locating a JDK and running its tools; shared by `jlink`, `jdeps` and `jlinkFindModules`. */
object JdkTools {
    /** The JDK home in an extracted archive or installation (macOS nests it under `Contents/Home`). */
    fun home(dir: File): File = listOf(dir, dir.resolve("Contents/Home")).firstOrNull { it.resolve("release").isFile }
        ?: throw GradleException("No JDK found in $dir (expected a 'release' file)")

    /** `JAVA_VERSION` from the home's `release` file, e.g. `21.0.13`. */
    fun javaVersion(home: File): String? = Properties()
        .apply { home.resolve("release").inputStream().use(::load) }
        .getProperty("JAVA_VERSION")
        ?.trim('"')

    fun tool(home: File, name: String): File =
        home.resolve("bin/$name").let { if (it.isFile) it else home.resolve("bin/$name.exe") }

    /** Links [request]'s modules with its tool JDK's jlink. The two JDKs must be the same exact release. */
    fun link(exec: ExecOperations, request: JlinkRequest) {
        val toolVersion = javaVersion(request.toolHome)
        val targetVersion = javaVersion(request.targetHome)
        if (toolVersion != targetVersion) {
            throw GradleException(
                "jlink needs the same JDK release on both sides: ${request.toolHome} is $toolVersion but " +
                    "${request.targetHome} is $targetVersion. Point both at the same version.",
            )
        }
        val jmods = request.targetHome.resolve("jmods")
        exec.exec {
            executable = tool(request.toolHome, "jlink").absolutePath
            if (jmods.isDirectory) args("--module-path", jmods.absolutePath)
            args("--add-modules", request.modules.joinToString(","))
            args(request.options)
            args("--output", request.output.absolutePath)
        }
    }
}

/** One jlink run: [toolHome]'s jlink links [modules] from [targetHome]'s jmods into [output]. */
data class JlinkRequest(
    val toolHome: File,
    val targetHome: File,
    val modules: Collection<String>,
    val options: List<String>,
    val output: File,
)
