package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** A JDK on disk: its directory, and the task that puts it there, if any. */
internal class JdkHome(val dir: Provider<Directory>, val builtBy: TaskProvider<Sync>?)

/**
 * The JDK for each target, set up once: a directory configured by path is used as it is, an archive
 * configured by path is extracted, and otherwise the pinned JDK is resolved from [JdkSources].
 * Called once the build script has run, when the Java release is final.
 */
internal class Jdks(
    private val project: Project,
    private val extension: JavaAppExtension,
    private val wiring: AppWiring,
) {
    private val homes = mutableMapOf<Target, JdkHome>()

    fun forTarget(target: Target): JdkHome = homes.getOrPut(target) { setUp(target) }

    private fun setUp(target: Target): JdkHome {
        val feature = JdkCatalog.requireSupported(extension.javaVersion.get())
        val local = JdkSources.localJdk(project.providers, feature, target)
        return when {
            local == null -> extracted(target, downloaded(target))
            local.isDirectory -> JdkHome(project.layout.dir(project.provider { local }), builtBy = null)
            local.isFile -> extracted(target, project.provider { local })
            else -> throw GradleException("${JdkSources.pathProperty(feature, target)} is $local, which does not exist")
        }
    }

    /** The pinned JDK archive for [target], resolved like any other dependency. */
    private fun downloaded(target: Target): Provider<File> {
        val sources = JdkSources.from(project.providers)
        val artifact = extension.jdkVersion.map { sources.artifact(JdkVersion(it), target) }
        val configuration = wiring.runtimeArchive("jdk${target.taskSuffix}", artifact)
        return wiring.singleFile(configuration)
    }

    private fun extracted(target: Target, archive: Provider<File>): JdkHome {
        val destination = wiring.workDir.map { it.dir("jdk/${target.id}") }
        val task = wiring.extractRuntime("extractJdk${target.taskSuffix}", archive, destination)
        return JdkHome(project.layout.dir(task.map { it.destinationDir }), task)
    }
}
