package io.github.fiftieshousewife.platform

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds throwaway projects against a fake "public" runtime source on disk, so the functional
 * tests need no network: the JDK running the tests and the `node` on PATH are repackaged in the
 * Adoptium / nodejs.org layouts the plugin resolves from.
 */
class TestProject(
    val dir: File,
) {
    private val sources = dir.resolve("sources")
    val project = dir.resolve("project").apply { mkdirs() }

    val jdkVersion: String = Runtime.version().let { v -> v.version().joinToString(".") + "+" + v.build().orElse(1) }

    /**
     * The only Java feature release the fake repository holds, because the fixture repackages the
     * JDK running the tests. A build under test must ask for this release, not the catalogue
     * default, or it looks for a JDK the fixture never published.
     */
    val javaFeature: Int = Runtime.version().feature()
    val nodeVersion: String by lazy { run(nodeExecutable(), "--version").trim().removePrefix("v") }

    init {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "fixtures use the system tar")
    }

    fun gradleProperties(vararg extra: Pair<String, String>) {
        val props =
            mapOf(
                "org.gradle.configuration-cache" to "true",
                "org.gradle.java.installations.auto-detect" to "false",
                "platform.repositories" to "public",
                "platform.public.jdkUrl" to sources.resolve("adoptium").toURI().toString(),
                "platform.public.nodeUrl" to sources.resolve("nodejs").toURI().toString(),
                "platform.runtime.jdk.${Runtime.version().feature()}" to jdkVersion,
            ) + extra
        project.resolve("gradle.properties").writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    fun settings(name: String = "app") =
        project.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.fiftieshousewife.platform.settings") }
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

    /** Publishes the test JDK as `OpenJDK<f>U-jdk_<arch>_<os>_hotspot_<v>.tar.gz` for each platform id. */
    fun fakeJdk(vararg platforms: String = arrayOf(hostPlatform())) {
        val home = File(System.getProperty("java.home"))
        assumeTrue(home.resolve("jmods").isDirectory, "the test JDK must ship jmods/")
        val feature = Runtime.version().feature()
        val stage = dir.resolve("stage/jdk-$jdkVersion")
        if (!stage.exists()) {
            stage.mkdirs()
            // Dereference links (distro JDKs link into /etc); dangling ones are skipped, not fatal.
            run("sh", "-c", "cd '${home.absolutePath}' && tar -chf - . 2>/dev/null | (cd '${stage.absolutePath}' && tar -xf -)")
        }
        platforms.forEach { p ->
            val (os, arch) = p.split("-")
            val out =
                sources
                    .resolve("adoptium/temurin$feature-binaries/releases/download/jdk-$jdkVersion")
                    .resolve("OpenJDK${feature}U-jdk_${arch}_${os}_hotspot_${jdkVersion.replace('+', '_')}.tar.gz")
            out.parentFile.mkdirs()
            run("tar", "-czf", out.absolutePath, "-C", stage.parentFile.absolutePath, stage.name)
        }
    }

    /** Publishes the `node` on PATH (plus its npm) in the nodejs.org/dist layout. */
    fun fakeNode() {
        val node = File(nodeExecutable()).canonicalFile
        val prefix = node.parentFile.parentFile
        val npm = prefix.resolve("lib/node_modules/npm")
        assumeTrue(npm.isDirectory, "npm must sit next to node at $npm")
        val classifier = hostPlatform().replace("mac", "darwin").replace("aarch64", "arm64")
        val top = "node-v$nodeVersion-$classifier"
        val stage =
            dir.resolve("stage/$top").apply {
                resolve("bin").mkdirs()
                resolve("lib/node_modules").mkdirs()
            }
        node.copyTo(stage.resolve("bin/node")).setExecutable(true)
        run("cp", "-R", npm.absolutePath, stage.resolve("lib/node_modules/npm").absolutePath)
        val out = sources.resolve("nodejs/v$nodeVersion/$top.tar.gz").apply { parentFile.mkdirs() }
        run("tar", "-czf", out.absolutePath, "-C", stage.parentFile.absolutePath, top)
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
        fun hostPlatform(): String {
            val os = System.getProperty("os.name").lowercase().let { if (it.startsWith("mac")) "mac" else "linux" }
            val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x64"
            return "$os-$arch"
        }

        fun nodeExecutable(): String =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .map { File(it, "node") }
                .firstOrNull { it.canExecute() }
                ?.absolutePath
                .also { assumeTrue(it != null, "node is not on PATH") }!!

        fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) { "${command.joinToString(" ")} failed:\n$output" }
            return output
        }
    }
}
