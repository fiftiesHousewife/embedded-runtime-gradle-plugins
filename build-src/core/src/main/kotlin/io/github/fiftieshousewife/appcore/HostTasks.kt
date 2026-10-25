package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.RunApp
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import java.time.Duration

/** `appSmokeTest` and `runApp`: the tasks that start this machine's archive, once it is known. */
internal class HostTasks(private val project: Project, private val wiring: AppWiring) {
    private val extension = wiring.extension

    val smoke: TaskProvider<AppSmokeTest> =
        project.tasks.register("appSmokeTest", AppSmokeTest::class.java) {
            group = AppWiring.VERIFICATION_GROUP
            description = "Extracts this machine's archive, starts it and waits for its healthcheck"
            healthTimeout.set(extension.smokeTestTimeout)
            resultFile.set(wiring.workDir.map { it.file("smoke-test.txt") })
            val enabled = extension.smokeTestEnabled
            onlyIf("${wiring.ecosystem.extensionName}.smokeTestEnabled is true") { enabled.get() }
        }

    private val runApp: TaskProvider<RunApp> =
        project.tasks.register("runApp", RunApp::class.java) {
            group = AppWiring.APPLICATION_GROUP
            description = "Runs this machine's assembled app in the foreground (bin/<app> run)"
            appName.set(extension.appName)
            windows.set(wiring.host.isWindows)
            args.convention(
                project.providers.gradleProperty("args")
                    .map { it.split(" ").filter(String::isNotBlank) }
                    .orElse(emptyList()),
            )
            val dir = appDir
            onlyIf("an archive is built for this machine") { dir.isPresent }
        }

    /** This machine's archive is the one `appSmokeTest` starts and `runApp` runs. */
    fun use(hostTasks: TargetTasks) {
        smoke.configure { archiveFiles.from(hostTasks.archive.flatMap { it.archiveFile }) }
        runApp.configure {
            dependsOn(hostTasks.assemble)
            appDir.set(project.layout.dir(hostTasks.assemble.map { it.destinationDir }))
        }
    }

    companion object {
        val DEFAULT_SMOKE_TEST_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
