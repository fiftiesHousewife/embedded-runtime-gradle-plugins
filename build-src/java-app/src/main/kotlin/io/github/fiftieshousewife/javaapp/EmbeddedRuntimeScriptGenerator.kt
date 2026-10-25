package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.tasks.LauncherTemplate
import org.gradle.jvm.application.scripts.JavaAppStartScriptGenerationDetails
import org.gradle.jvm.application.scripts.ScriptGenerator
import java.io.Serializable
import java.io.Writer

/**
 * The script generator the java-app plugin sets on Gradle's own `startScripts` task: the same
 * `bin/<app>` and `bin/<app>.bat` as Gradle's, but starting the embedded runtime instead of
 * whatever `JAVA_HOME` or `PATH` finds, with `run`, `healthcheck` and `version`.
 *
 * It uses what Gradle passes every generator: the main class, the classpath (the app's own jar
 * first) and `defaultJvmOpts`. A build can replace it the usual way, with
 * `tasks.startScripts { unixStartScriptGenerator = … }`; the smoke test still has to pass.
 */
class EmbeddedRuntimeScriptGenerator(private val windows: Boolean, private val lockedOptions: Boolean) :
    ScriptGenerator,
    Serializable {
    override fun generateScript(details: JavaAppStartScriptGenerationDetails, destination: Writer) {
        val launch =
            JavaLaunch(
                mainClass = details.mainClassName,
                appJar = details.classpath.first().substringAfterLast('/'),
                jvmArgs = details.defaultJvmOpts.toList(),
            )
        val exec = if (windows) JavaLaunchCommand.windows(launch) else JavaLaunchCommand.posix(launch)
        destination.write(LauncherTemplate.render(windows, details.applicationName, lockedOptions, exec))
    }

    private companion object {
        private const val serialVersionUID = 1L
    }
}
