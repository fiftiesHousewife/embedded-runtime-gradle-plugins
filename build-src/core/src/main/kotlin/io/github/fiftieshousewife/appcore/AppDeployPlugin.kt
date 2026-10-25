package io.github.fiftieshousewife.appcore

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `io.github.fiftieshousewife.app-deploy` — installs a published app on the machine the build runs
 * on, for a deployment agent or a server with Gradle:
 *
 * ```
 * dependencies { app("com.example:orders-service:1.4.2") }
 * appDeploy {
 *     dir = file("/srv/orders")
 *     restartCommand("systemctl", "--user", "restart", "orders")
 * }
 * ```
 *
 * `appInstall` resolves the archive built for this machine's OS and CPU and installs it exactly as
 * the producing build's own `appInstall` does: it becomes `current` only after starting healthy.
 * `appRollback` goes back to the previous release.
 */
abstract class AppDeployPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("base")
        val declared =
            project.configurations.dependencyScope("app") {
                description = "The published app to install on this machine (one coordinate)"
            }
        val settings = project.extensions.create("appDeploy", InstallSettings::class.java)
        settings.applyConventions(project, "appDeploy.installDir")
        Installation.register(project, settings, "appDeploy").configure {
            archiveFiles.from(MachineArchives.resolvable(project, "appArchives", declared))
        }
    }
}
