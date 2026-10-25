package io.github.fiftieshousewife.appcore.tasks

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Proves the published archive itself works, not the build directories it came from:
 *
 * 1. extracts the archive into a scratch directory;
 * 2. `bin/<app> version` must report the expected version;
 * 3. `bin/<app> run` is started with a free `APP_PORT`;
 * 4. `bin/<app> healthcheck` must exit 0 within [healthTimeout];
 * 5. the process tree is stopped.
 *
 * Only possible when the archive targets the build host; for cross builds the task is skipped and
 * publishing is guarded (see [VerifyPublishable]).
 */
@DisableCachingByDefault(because = "Executes the application; must run on the machine that publishes")
abstract class AppSmokeTest
    @Inject
    constructor(
        private val fs: FileSystemOperations,
        private val archives: ArchiveOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val archive: RegularFileProperty

        @get:Input abstract val appName: Property<String>

        @get:Input abstract val expectedVersion: Property<String>

        @get:Input abstract val windows: Property<Boolean>

        @get:Input abstract val healthTimeout: Property<Duration>

        @get:OutputFile abstract val resultFile: RegularFileProperty

        @TaskAction
        fun smokeTest() {
            val archiveFile = archive.get().asFile
            val launcher = extractLauncher(archiveFile)
            val root =
                temporaryDir.resolve("root").apply {
                    deleteRecursively()
                    mkdirs()
                }
            val env =
                mapOf(
                    "APP_ROOT" to root.absolutePath,
                    "APP_PORT" to freePort().toString(),
                    "APP_LOG_TARGET" to "stdout",
                )

            checkVersion(launcher, env)

            val log = temporaryDir.resolve("run.log")
            val app =
                command(launcher, "run")
                    .directory(root)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .also { it.environment().putAll(env) }
                    .start()
            try {
                awaitHealthy(app, launcher, env, log)
            } finally {
                stop(app)
            }
            resultFile.get().asFile.writeText("healthy ${expectedVersion.get()}\n")
            logger.lifecycle("Smoke test passed: ${archiveFile.name} started and reported healthy")
        }

        /** Extracts [archiveFile] afresh and returns the launcher inside its single top-level directory. */
        private fun extractLauncher(archiveFile: File): File {
            val work = temporaryDir.resolve("extract")
            fs.sync {
                from(archives.tarTree(archiveFile))
                into(work)
            }
            val home =
                work.listFiles()?.singleOrNull { it.isDirectory }
                    ?: throw GradleException("Archive $archiveFile must contain exactly one top-level directory")
            val launcher = home.resolve(if (windows.get()) "bin/${appName.get()}.bat" else "bin/${appName.get()}")
            if (!launcher.isFile) throw GradleException("Launcher $launcher missing from archive")
            return launcher
        }

        private fun awaitHealthy(
            app: Process,
            launcher: File,
            env: Map<String, String>,
            log: File,
        ) {
            val deadline = System.nanoTime() + healthTimeout.get().toNanos()
            while (true) {
                if (!app.isAlive) {
                    throw GradleException("${appName.get()} exited with ${app.exitValue()} before becoming healthy:\n${tail(log)}")
                }
                if (runQuietly(launcher, "healthcheck", env, HEALTHCHECK_TIMEOUT) == 0) return
                if (System.nanoTime() > deadline) {
                    throw GradleException("${appName.get()} not healthy within ${healthTimeout.get().seconds}s:\n${tail(log)}")
                }
                Thread.sleep(POLL_INTERVAL.toMillis())
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun checkVersion(
            launcher: File,
            env: Map<String, String>,
        ) {
            val out = temporaryDir.resolve("version.json")
            val process =
                command(launcher, "version")
                    .redirectOutput(out)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { it.environment().putAll(env) }
                    .start()
            if (!process.waitFor(VERSION_TIMEOUT.seconds, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw GradleException("'${launcher.name} version' failed")
            }
            val reported = (JsonSlurper().parse(out) as Map<String, Any?>)["version"]
            if (reported != expectedVersion.get()) {
                throw GradleException("'${launcher.name} version' reported $reported, expected ${expectedVersion.get()}")
            }
        }

        private fun runQuietly(
            launcher: File,
            cmd: String,
            env: Map<String, String>,
            limit: Duration,
        ): Int {
            val p =
                command(launcher, cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .also { it.environment().putAll(env) }
                    .start()
            if (!p.waitFor(limit.seconds, TimeUnit.SECONDS)) {
                stop(p)
                return -1
            }
            return p.exitValue()
        }

        private fun command(
            launcher: File,
            cmd: String,
        ) = ProcessBuilder(
            if (windows.get()) listOf("cmd.exe", "/c", launcher.absolutePath, cmd) else listOf(launcher.absolutePath, cmd),
        )

        /** Stops the launcher and everything it started (on Windows the launcher is cmd.exe, not the runtime). */
        private fun stop(process: Process) {
            val tree = process.toHandle().descendants().toList() + process.toHandle()
            tree.forEach { it.destroy() }
            if (!process.waitFor(STOP_TIMEOUT.seconds, TimeUnit.SECONDS)) tree.forEach { it.destroyForcibly() }
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private fun tail(log: File): String = if (log.isFile) log.readLines().takeLast(LOG_TAIL_LINES).joinToString("\n") else "(no output)"

        private companion object {
            val VERSION_TIMEOUT: Duration = Duration.ofSeconds(30)
            val HEALTHCHECK_TIMEOUT: Duration = Duration.ofSeconds(10)
            val STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
            val POLL_INTERVAL: Duration = Duration.ofMillis(500)
            const val LOG_TAIL_LINES = 40
        }
    }

/** Fails publication of an archive that was not smoke-tested here, unless explicitly allowed. */
@DisableCachingByDefault(because = "Verification only")
abstract class VerifyPublishable : DefaultTask() {
    @get:Input abstract val crossBuild: Property<Boolean>

    @get:Input abstract val smokeTestEnabled: Property<Boolean>

    @get:Input abstract val allowUntested: Property<Boolean>

    /** The Gradle property that overrides this guard, named in the failure message. */
    @get:Input abstract val allowUntestedProperty: Property<String>

    @get:Input abstract val target: Property<String>

    @TaskAction
    fun verify() {
        val untested = crossBuild.get() || !smokeTestEnabled.get()
        if (!untested) return
        val why = if (crossBuild.get()) "it is a cross build for ${target.get()}" else "its smoke test is disabled"
        if (allowUntested.get()) {
            logger.warn("Publishing an untested archive because $why (${allowUntestedProperty.get()}=true)")
        } else {
            throw GradleException(
                "Refusing to publish: $why, so the archive was never started. Publish from a ${target.get()} " +
                    "build (CI), or set -P${allowUntestedProperty.get()}=true to override.",
            )
        }
    }
}
