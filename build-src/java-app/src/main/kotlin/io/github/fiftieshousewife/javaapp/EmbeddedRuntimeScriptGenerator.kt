package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate
import org.gradle.jvm.application.scripts.JavaAppStartScriptGenerationDetails
import org.gradle.jvm.application.scripts.ScriptGenerator
import java.io.Serializable
import java.io.Writer

/**
 * Gradle's `bin/<app>` and `bin/<app>.bat`, but starting the embedded runtime instead of whatever `JAVA_HOME` or
 * `PATH` finds. A build can replace it on `startScripts` as usual; the smoke test still has to pass.
 */
class EmbeddedRuntimeScriptGenerator(
    private val windows: Boolean,
    private val lockedOptions: Boolean,
    private val logDirFiles: Boolean = false,
) : ScriptGenerator,
    Serializable {
    override fun generateScript(details: JavaAppStartScriptGenerationDetails, destination: Writer) {
        val launch =
            JavaLaunch(
                mainClass = details.mainClassName,
                appJar = details.classpath.first().substringAfterLast('/'),
                jvmArgs = details.defaultJvmOpts.toList(),
                logDirFiles = logDirFiles,
            )
        val exec = if (windows) JavaLaunchCommand.windows(launch) else JavaLaunchCommand.posix(launch)
        destination.write(LauncherTemplate.render(windows, details.applicationName, lockedOptions, exec))
    }

    private companion object {
        private const val serialVersionUID = 1L
    }
}
