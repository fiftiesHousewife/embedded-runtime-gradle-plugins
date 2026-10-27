package io.github.fiftieshousewife.nodeapp.tasks

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.process.ExecSpec
import java.io.File

object NodeLayout {
    data class Tools(val node: File, val npmCli: File, val binDir: File)

    /** The POSIX layout keeps node in `bin/`; the Windows zip keeps `node.exe` at the top. */
    fun locate(home: File): Tools {
        val posix = home.resolve("bin/node")
        val windows = home.resolve("node.exe")
        return when {
            posix.isFile -> Tools(posix, home.resolve("lib/node_modules/npm/bin/npm-cli.js"), posix.parentFile)
            windows.isFile -> Tools(windows, home.resolve("node_modules/npm/bin/npm-cli.js"), home)
            else -> throw GradleException("No Node.js distribution found in $home")
        }
    }

    /** `node`'s `bin` directory goes first on `PATH` so package scripts that call `node` get the same version. */
    fun npm(spec: ExecSpec, tools: Tools, args: List<String>, npmrc: File?) {
        spec.executable = tools.node.absolutePath
        spec.args(tools.npmCli.absolutePath)
        spec.args(args)
        spec.args(configArgs(npmrc))
        spec.environment("PATH", tools.binDir.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty())
    }

    /** CLI flags beat every other npm config source, so a developer's `~/.npmrc` can't redirect the build. */
    internal fun configArgs(npmrc: File?): List<String> =
        listOfNotNull(npmrc?.let { "--userconfig=${it.absolutePath}" }) +
            listOf("--no-audit", "--no-fund", "--no-update-notifier")

    fun hasScript(packageJson: File, script: String): Boolean {
        if (!packageJson.isFile) return false
        val scripts = (JsonSlurper().parse(packageJson) as Map<*, *>)["scripts"] as? Map<*, *>
        return scripts?.containsKey(script) == true
    }
}
