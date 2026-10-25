package io.github.fiftieshousewife.nodeapp.tasks

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import javax.inject.Inject

/** Locates `node` and npm's CLI inside an extracted Node.js distribution (POSIX or Windows layout). */
object NodeLayout {
    data class Tools(
        val node: File,
        val npmCli: File,
        val binDir: File,
    )

    fun locate(home: File): Tools {
        val posix = home.resolve("bin/node")
        if (posix.isFile) {
            return Tools(posix, home.resolve("lib/node_modules/npm/bin/npm-cli.js"), posix.parentFile)
        }
        val windows = home.resolve("node.exe")
        if (windows.isFile) {
            return Tools(windows, home.resolve("node_modules/npm/bin/npm-cli.js"), home)
        }
        throw GradleException("No Node.js distribution found in $home")
    }

    /**
     * Runs npm's CLI with the distribution's own `node`, whose `bin` directory goes first on `PATH`
     * so package scripts that call `node` get the same version.
     */
    fun npm(
        spec: ExecSpec,
        tools: Tools,
        args: List<String>,
        npmrc: File?,
    ) {
        spec.executable = tools.node.absolutePath
        spec.args(tools.npmCli.absolutePath)
        spec.args(args)
        spec.args(registryNpmArgs(npmrc))
        spec.environment("PATH", tools.binDir.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty())
    }

    /**
     * Passed on the command line because CLI flags take precedence over every other npm config
     * source; a developer's `NPM_CONFIG_USERCONFIG` or `~/.npmrc` must not redirect the build to
     * another registry.
     */
    private fun registryNpmArgs(npmrc: File?): List<String> =
        listOfNotNull(npmrc?.let { "--userconfig=${it.absolutePath}" }) +
            listOf("--no-audit", "--no-fund", "--no-update-notifier")

    @Suppress("UNCHECKED_CAST")
    fun hasScript(
        packageJson: File,
        script: String,
    ): Boolean {
        if (!packageJson.isFile) return false
        val scripts = (JsonSlurper().parse(packageJson) as Map<String, Any?>)["scripts"] as? Map<String, Any?>
        return scripts?.containsKey(script) == true
    }
}

/** Runs npm with the pinned Node.js distribution, never one from `PATH`. */
@DisableCachingByDefault(because = "npm maintains its own cache; outputs are declared per task instance")
abstract class NpmTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Internal abstract val nodeHome: DirectoryProperty

        @get:Input abstract val nodeVersion: Property<String>

        @get:Internal abstract val workingDirectory: DirectoryProperty

        @get:Input abstract val npmArgs: ListProperty<String>

        @get:Input abstract val environment: MapProperty<String, String>

        /** Not fingerprinted: it carries credentials, and the registry it names does not change what npm installs. */
        @get:Internal abstract val npmrc: RegularFileProperty

        @TaskAction
        fun run() {
            val tools = NodeLayout.locate(nodeHome.get().asFile)
            exec.exec {
                workingDir(workingDirectory.get().asFile)
                environment(this@NpmTask.environment.get())
                NodeLayout.npm(this, tools, npmArgs.get(), npmrc.orNull?.asFile)
            }
        }
    }

/**
 * Production `npm ci --omit=dev` into an isolated directory, so packaging never sees development
 * dependencies or the developer's own `node_modules`.
 */
@CacheableTask
abstract class NpmProductionInstall
    @Inject
    constructor(
        private val exec: ExecOperations,
        private val fs: FileSystemOperations,
    ) : DefaultTask() {
        @get:Internal abstract val nodeHome: DirectoryProperty

        @get:Input abstract val nodeVersion: Property<String>

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val packageJson: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val packageLock: RegularFileProperty

        @get:Input abstract val npmArgs: ListProperty<String>

        @get:Internal abstract val npmrc: RegularFileProperty

        @get:OutputDirectory abstract val outputDir: DirectoryProperty

        @TaskAction
        fun install() {
            val dir = outputDir.get().asFile
            fs.delete { delete(dir) }
            dir.mkdirs()
            packageJson.get().asFile.copyTo(dir.resolve("package.json"))
            packageLock.get().asFile.copyTo(dir.resolve("package-lock.json"))

            val tools = NodeLayout.locate(nodeHome.get().asFile)
            exec.exec {
                workingDir(dir)
                NodeLayout.npm(this, tools, listOf("ci", "--omit=dev") + npmArgs.get(), npmrc.orNull?.asFile)
            }
            // An application without dependencies gets no node_modules from npm; downstream tasks
            // (SBOM, verification, assembly) treat it as an empty but present directory.
            dir.resolve("node_modules").mkdirs()
        }
    }

/**
 * Writes the npm user config pointing at the configured registry, with Artifactory credentials
 * when present. It is never up to date or cached because it carries a secret; the file is written
 * owner-readable only and lives under `build/`.
 */
@UntrackedTask(because = "Contains credentials that must not be cached or fingerprinted")
abstract class GenerateNpmrc : DefaultTask() {
    @get:Input abstract val registry: Property<String>

    @get:Input @get:Optional
    abstract val caFile: Property<String>

    @get:Internal abstract val credentials: Property<PasswordCredentials>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val registry = registry.get().trimEnd('/') + "/"
        // replace-registry-host=always: lockfiles record registry.npmjs.org tarball URLs; fetch them
        // from the configured registry instead, whichever host the lockfile was generated against.
        val lines = mutableListOf("registry=$registry", "replace-registry-host=always")
        caFile.orNull?.let { lines += "cafile=$it" }
        credentials.orNull?.let { c ->
            val auth = Base64.getEncoder().encodeToString("${c.username}:${c.password}".toByteArray())
            val uri = URI.create(registry)
            val prefix = "//${uri.authority}${uri.path}"
            lines += "$prefix:_auth=$auth"
        }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }
}

/**
 * Checks a production `node_modules` against the target, using npm's hidden lockfile.
 *
 * Packages with install scripts (node-gyp builds, prebuild downloads) produce host-specific
 * binaries, so they make a cross build wrong. On a host build they are reported when
 * `--ignore-scripts` skipped them, because the package may then be incomplete at runtime.
 */
@DisableCachingByDefault(because = "Verification only")
abstract class VerifyNodeModules : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nodeModules: DirectoryProperty

    @get:Input abstract val crossBuild: Property<Boolean>

    @get:Input abstract val ignoredScripts: Property<Boolean>

    @get:Input abstract val portable: ListProperty<String>

    @get:OutputFile abstract val reportFile: RegularFileProperty

    @Suppress("UNCHECKED_CAST")
    @TaskAction
    fun verify() {
        val lock = nodeModules.get().asFile.resolve(".package-lock.json")
        val packages =
            if (lock.isFile) {
                (JsonSlurper().parse(lock) as Map<String, Any?>)["packages"] as? Map<String, Map<String, Any?>> ?: emptyMap()
            } else {
                emptyMap()
            }
        val withScripts =
            packages
                .filterValues { it["hasInstallScript"] == true }
                .keys
                .map { it.substringAfterLast("node_modules/") }
                .filterNot { it in portable.get() }
                .sorted()

        reportFile.get().asFile.writeText(
            if (withScripts.isEmpty()) "portable\n" else withScripts.joinToString("\n", postfix = "\n"),
        )
        if (withScripts.isEmpty()) return
        if (crossBuild.get()) {
            throw GradleException(
                "Cannot cross-build: these packages have install scripts that build or download " +
                    "host-specific binaries: ${withScripts.joinToString()}. Build this app on its target (CI).",
            )
        }
        if (ignoredScripts.get()) {
            logger.warn(
                "Install scripts were skipped (--ignore-scripts) for: ${withScripts.joinToString()}. " +
                    "If they are needed at runtime, set nodeApp.ignoreInstallScripts = false after vetting them.",
            )
        }
    }
}
