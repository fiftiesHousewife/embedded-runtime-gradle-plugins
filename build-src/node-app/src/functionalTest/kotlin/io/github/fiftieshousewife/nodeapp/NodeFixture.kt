package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/** The `node` on PATH (plus its npm), repackaged in the nodejs.org/dist layout under [TestProject.sources]. */
object NodeFixture {
    val nodeVersion: String by lazy { TestProject.run(nodeExecutable(), "--version").trim().removePrefix("v") }

    /** Points the build at the fake site and pins [nodeVersion]. */
    fun properties(project: TestProject) =
        arrayOf(
            NodeSources.PUBLIC_URL to
                project.sources
                    .resolve("nodejs")
                    .toURI()
                    .toString(),
            NodeSources.VERSION to nodeVersion,
        )

    fun publish(project: TestProject) {
        val node = File(nodeExecutable()).canonicalFile
        val npm = npmPackage(node)
        val classifier = TestProject.hostTarget().replace("mac", "darwin").replace("aarch64", "arm64")
        val top = "node-v$nodeVersion-$classifier"
        val stage =
            project.dir.resolve("stage/$top").apply {
                resolve("bin").mkdirs()
                resolve("lib/node_modules").mkdirs()
            }
        node.copyTo(stage.resolve("bin/node")).setExecutable(true)
        TestProject.run("cp", "-R", npm.absolutePath, stage.resolve("lib/node_modules/npm").absolutePath)
        val out = project.sources.resolve("nodejs/v$nodeVersion/$top.tar.gz").apply { parentFile.mkdirs() }
        TestProject.run("tar", "-czf", out.absolutePath, "-C", stage.parentFile.absolutePath, top)
    }

    /**
     * The npm package directory. nodejs.org archives keep it next to node (`lib/node_modules/npm`);
     * Homebrew installs it under its own prefix, reachable by following the `npm` on PATH to
     * `…/node_modules/npm/bin/npm-cli.js`.
     */
    private fun npmPackage(node: File): File {
        val besideNode = node.parentFile.parentFile.resolve("lib/node_modules/npm")
        val onPath =
            onPath("npm")
                ?.canonicalFile
                ?.takeIf { it.name == "npm-cli.js" }
                ?.parentFile
                ?.parentFile
        val npm = listOfNotNull(besideNode, onPath).firstOrNull { it.resolve("package.json").isFile }
        assumeTrue(npm != null, "no npm package found beside $node or on PATH")
        return npm!!
    }

    private fun nodeExecutable(): String = onPath("node")?.absolutePath.also { assumeTrue(it != null, "node is not on PATH") }!!

    private fun onPath(name: String): File? =
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
}
