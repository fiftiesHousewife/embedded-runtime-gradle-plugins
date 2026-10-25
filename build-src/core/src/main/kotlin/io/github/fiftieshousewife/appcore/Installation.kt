package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppInstall
import io.github.fiftieshousewife.appcore.tasks.AppRollback
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/** Registers `appInstall` and `appRollback` for [InstallSettings]; the caller supplies the archive. */
internal object Installation {
    const val GROUP = "deployment"

    fun register(project: Project, settings: InstallSettings, block: String): TaskProvider<AppInstall> {
        project.tasks.register("appRollback", AppRollback::class.java) {
            group = GROUP
            description = "Switches the install back to its previous release ($block install.dir)"
            installDir.set(settings.dir)
            restartCommand.set(settings.restartCommand)
        }
        return project.tasks.register("appInstall", AppInstall::class.java) {
            group = GROUP
            description = "Installs this machine's archive into $block install.dir once it has started healthy"
            installDir.set(settings.dir)
            keep.set(settings.keep)
            restartCommand.set(settings.restartCommand)
            healthTimeout.set(settings.healthTimeout)
        }
    }
}
