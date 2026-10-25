package io.github.fiftieshousewife.appcore

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds throwaway projects against a fake "public" runtime source on disk, so the functional
 * tests need no network: each plugin's tests repackage a local runtime under [sources] in the
 * layout that plugin resolves from. Those fixtures use the system `tar`, so a project runs on
 * Windows only when a test asks for it with `runsOnWindows` and needs no fixture archive.
 */
class TestProject(val dir: File, runsOnWindows: Boolean = false) {
    /** Root of the fake runtime download site. */
    val sources = dir.resolve("sources")
    val project = dir.resolve("project").apply { mkdirs() }

    init {
        assumeTrue(runsOnWindows || !isWindows, "fixtures use the system tar")
    }

    fun gradleProperties(vararg extra: Pair<String, String>) {
        val props =
            mapOf(
                "org.gradle.configuration-cache" to "true",
                "org.gradle.java.installations.auto-detect" to "false",
            ) + extra
        project
            .resolve(
                "gradle.properties",
            ).writeText(props.entries.joinToString("\n") { "${it.key}=${it.value.replace("\\", "\\\\")}" } + "\n")
    }

    fun settings(settingsPluginId: String, name: String = "app") = project.resolve("settings.gradle.kts").writeText(
        """
        plugins { id("$settingsPluginId") }
        rootProject.name = "$name"
        """.trimIndent(),
    )

    /** A settings script that applies [settingsPluginIds] and prints every repository they configure. */
    fun repositoryProbe(vararg settingsPluginIds: String) = file(
        "settings.gradle.kts",
        TestProject::class.java
            .getResource("/fixtures/repository-probe/settings.gradle.kts.template")!!
            .readText()
            .replace("@PLUGINS@", settingsPluginIds.joinToString("; ") { "id(\"$it\")" }),
    )

    /** The `REPO <name> <url>` and `PLUGINS <name>` lines a [repositoryProbe] build printed. */
    fun repositories(output: String) = output.lines().filter { it.startsWith("REPO ") || it.startsWith("PLUGINS ") }

    fun file(path: String, content: String) = project.resolve(path).apply {
        parentFile.mkdirs()
        writeText(content.trimIndent() + "\n")
    }

    fun build(vararg args: String): BuildResult = runner(*args).build()

    fun buildAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private fun runner(vararg args: String) = GradleRunner
        .create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .forwardOutput()

    companion object {
        /** The build host as a target id, e.g. `mac-aarch64`. */
        fun hostTarget(): String = Target.host().id

        val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

        fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) {
                "${command.joinToString(" ")} failed:\n$output"
            }
            return output
        }
    }
}

/** Many checks for one `assertAll`, so every failure is reported together. */
fun checks(checks: List<() -> Unit>): List<org.junit.jupiter.api.function.Executable> =
    checks.map { check -> org.junit.jupiter.api.function.Executable { check() } }
