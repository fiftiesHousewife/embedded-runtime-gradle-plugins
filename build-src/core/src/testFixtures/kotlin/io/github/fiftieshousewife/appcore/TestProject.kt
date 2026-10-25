package io.github.fiftieshousewife.appcore

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds throwaway projects against a fake "public" runtime source on disk, so the functional
 * tests need no network: each plugin's tests repackage a local runtime under [sources] in the
 * layout that plugin resolves from.
 */
class TestProject(
    val dir: File,
) {
    /** Root of the fake runtime download site. */
    val sources = dir.resolve("sources")
    val project = dir.resolve("project").apply { mkdirs() }

    init {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "fixtures use the system tar")
    }

    fun gradleProperties(vararg extra: Pair<String, String>) {
        val props =
            mapOf(
                "org.gradle.configuration-cache" to "true",
                "org.gradle.java.installations.auto-detect" to "false",
            ) + extra
        project.resolve("gradle.properties").writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    fun settings(
        settingsPluginId: String,
        name: String = "app",
    ) = project.resolve("settings.gradle.kts").writeText(
        """
        plugins { id("$settingsPluginId") }
        rootProject.name = "$name"
        """.trimIndent(),
    )

    fun file(
        path: String,
        content: String,
    ) = project.resolve(path).apply {
        parentFile.mkdirs()
        writeText(content.trimIndent() + "\n")
    }

    fun build(vararg args: String): BuildResult = runner(*args).build()

    fun buildAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .forwardOutput()

    companion object {
        /** The build host as a target id, e.g. `mac-aarch64`. */
        fun hostTarget(): String {
            val os = System.getProperty("os.name").lowercase().let { if (it.startsWith("mac")) "mac" else "linux" }
            val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x64"
            return "$os-$arch"
        }

        fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) { "${command.joinToString(" ")} failed:\n$output" }
            return output
        }
    }
}
