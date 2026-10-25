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
        .apply { Files.newInputStream(home.resolve("release").toPath()).use(::load) }
        .getProperty("JAVA_VERSION")
        ?.trim('"')

    /**
     * Refuses a JDK that isn't the pinned release, such as an installed one a patch behind: the
     * archive's manifest and SBOM name the pin. Vendors spell versions differently, so the `release`
     * file's `JAVA_VERSION` (`21.0.5`) must start the pin (`21.0.5`, `21.0.5+11`, `21.0.5.0.11`) or follow
     * `jdk` in it (Zulu's `21.38.21-ca-jdk21.0.5`).
     */
    fun requirePinned(pin: String, home: File) {
        val actual = javaVersion(home) ?: return
        // Whole version components only: 21.0.1 must not pass for 21.0.10.
        val matches = Regex("""(^|jdk)${Regex.escape(actual)}(\D|$)""").containsMatchIn(pin)
        if (matches) return
        throw GradleException(
            "The JDK at $home is $actual, but the build pins $pin (javaApp.jdk.<feature> or the catalogue). " +
                "Install $pin there, or pin the version that is installed.",
        )
    }

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
        val jmods = request.jmods ?: request.targetHome.resolve("jmods")
        exec.exec {
            executable = tool(request.toolHome, "jlink").absolutePath
            if (jmods.isDirectory) args("--module-path", jmods.absolutePath)
            args("--add-modules", request.modules.joinToString(","))
            args(request.options)
            args("--output", request.output.absolutePath)
        }
    }
}

/**
 * One jlink run: [toolHome]'s jlink links [modules] from [targetHome]'s jmods (or [jmods], when the
 * vendor ships them separately) into [output]. With no jmods at all, a JEP 493 JDK links from its
 * own runtime image, which works only for the machine it runs on.
 */
data class JlinkRequest(
    val toolHome: File,
    val targetHome: File,
    val modules: Collection<String>,
    val options: List<String>,
    val output: File,
    val jmods: File? = null,
)
