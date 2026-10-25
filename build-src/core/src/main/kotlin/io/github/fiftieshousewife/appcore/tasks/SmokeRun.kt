package io.github.fiftieshousewife.appcore.tasks

import groovy.json.JsonSlurper
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One start of an extracted app through its launcher, exactly as a server would run it:
 *
 * 1. `bin/<app> version` must report the expected version;
 * 2. `bin/<app> run` is started with a free `APP_PORT` and a scratch `APP_ROOT`;
 * 3. `bin/<app> healthcheck` must exit 0 within [healthTimeout];
 * 4. the process tree is stopped.
 *
 * Shared by `appSmokeTest`, which fails the build on a [Result.Failed], and the Java plugin's
 * `jlinkFindModules`, which reads the failure to decide which JDK module to add next.
 */
class SmokeRun(
    private val launcher: File,
    private val windows: Boolean,
    private val workDir: File,
    private val healthTimeout: Duration,
) {
    sealed interface Result {
        data object Healthy : Result

        data class Failed(
            val reason: String,
            /** Everything the app printed, for diagnosing what it was missing. */
            val output: String,
        ) : Result {
            val tail: String get() = output.lines().takeLast(LOG_TAIL_LINES).joinToString("\n")
        }
    }

    private val log = workDir.resolve("run.log")

    fun run(expectedVersion: String): Result {
        val root =
            workDir.resolve("root").apply {
                deleteRecursively()
                mkdirs()
            }
        val env =
            mapOf(
                "APP_ROOT" to root.absolutePath,
                "APP_PORT" to ServerSocket(0).use { it.localPort }.toString(),
                "APP_LOG_TARGET" to "stdout",
            )
        checkVersion(env, expectedVersion)?.let { return it }

        val app =
            command("run")
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .also { it.environment().putAll(env) }
                .start()
        try {
            return awaitHealthy(app, env)
        } finally {
            stop(app)
        }
    }

    private fun awaitHealthy(
        app: Process,
        env: Map<String, String>,
    ): Result {
        val deadline = System.nanoTime() + healthTimeout.toNanos()
        var outcome: Result? = null
        while (outcome == null) {
            outcome =
                when {
                    !app.isAlive -> failed("exited with ${app.exitValue()} before becoming healthy")
                    runQuietly("healthcheck", env, HEALTHCHECK_TIMEOUT) == 0 -> Result.Healthy
                    System.nanoTime() > deadline -> failed("not healthy within ${healthTimeout.seconds}s")
                    else -> null.also { Thread.sleep(POLL_INTERVAL.toMillis()) }
                }
        }
        return outcome
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkVersion(
        env: Map<String, String>,
        expected: String,
    ): Result? {
        val out = workDir.resolve("version.json")
        val process =
            command("version")
                .redirectOutput(out)
                .redirectErrorStream(false)
                .redirectError(log)
                .also { it.environment().putAll(env) }
                .start()
        if (!process.waitFor(VERSION_TIMEOUT.seconds, TimeUnit.SECONDS) || process.exitValue() != 0) {
            return failed("'${launcher.name} version' failed")
        }
        val reported = (JsonSlurper().parse(out) as Map<String, Any?>)["version"]
        return if (reported == expected) null else failed("'${launcher.name} version' reported $reported, expected $expected")
    }

    private fun runQuietly(
        cmd: String,
        env: Map<String, String>,
        limit: Duration,
    ): Int {
        val p =
            command(cmd)
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

    private fun command(cmd: String) =
        ProcessBuilder(if (windows) listOf("cmd.exe", "/c", launcher.absolutePath, cmd) else listOf(launcher.absolutePath, cmd))

    /** Stops the launcher and everything it started (on Windows the launcher is cmd.exe, not the runtime). */
    private fun stop(process: Process) {
        val tree = process.toHandle().descendants().toList() + process.toHandle()
        tree.forEach { it.destroy() }
        if (!process.waitFor(STOP_TIMEOUT.seconds, TimeUnit.SECONDS)) tree.forEach { it.destroyForcibly() }
    }

    private fun failed(reason: String) = Result.Failed(reason, if (log.isFile) log.readText() else "")

    private companion object {
        val VERSION_TIMEOUT: Duration = Duration.ofSeconds(30)
        val HEALTHCHECK_TIMEOUT: Duration = Duration.ofSeconds(10)
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val POLL_INTERVAL: Duration = Duration.ofMillis(500)
        const val LOG_TAIL_LINES = 40
    }
}
