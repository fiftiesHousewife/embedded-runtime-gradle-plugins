package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import java.io.File
import java.net.ServerSocket

/** Drives `deploy/install.sh` against one app root, on a free port, supervised with nohup. */
class Installer(private val root: File) {
    init {
        root.resolve("conf").mkdirs()
        root.resolve("conf/env").writeText("APP_PORT=${ServerSocket(0).use { it.localPort }}\n")
    }

    val current: String get() = root.resolve("current").canonicalFile.name

    fun config(name: String) = root.resolve("conf/$name")

    fun install(archive: File) = run("install", "--archive", archive.absolutePath, "--health-timeout", "60")

    fun status() = run("status")

    fun rollback() = run("rollback", "--health-timeout", "60")

    fun stop() = run("stop")

    private fun run(vararg args: String) = TestProject.run(
        "bash",
        System.getProperty("installScript"),
        *args,
        "--supervisor",
        "nohup",
        "--app-root",
        root.absolutePath,
    )
}
