package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.tasks.DownloadYumPackage
import io.github.fiftieshousewife.appcore.tasks.ExtractRpmRuntime
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * A JDK on disk: its directory, a separate `jmods/` directory when the vendor publishes one
 * ([JdkDistribution.separateJmods]), and the tasks that put them there, if any.
 */
internal class JdkHome(
    val dir: Provider<Directory>,
    val builtBy: TaskProvider<out Task>?,
    val jmods: Provider<Directory>? = null,
    private val jmodsBuiltBy: TaskProvider<out Task>? = null,
) {
    val tasks: List<TaskProvider<out Task>> get() = listOfNotNull(builtBy, jmodsBuiltBy)
}

/**
 * The JDK for each target, set up once: a directory configured by path is used as it is, an archive
 * configured by path is extracted, and otherwise the pinned JDK is resolved from [JdkSources]. An
 * RPM (by its `.rpm` name or the repository's pattern) is unpacked and its JDK home found inside.
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
            local == null -> downloadedJdk(target, feature)

            local.isDirectory -> JdkHome(project.layout.dir(project.provider { local }), builtBy = null)

            local.isFile -> extracted(target, project.provider { local }, rpm = local.name.endsWith(".rpm", true))

            else -> throw GradleException(
                "${JdkSources.pathProperty(feature, target)} is $local, which does not exist. In gradle.properties " +
                    "a backslash escapes the next character: write Windows paths as C:/Program Files/Java/jdk-21.",
            )
        }
    }

    /** The pinned JDK for [target], resolved like any other dependency, with its jmods if separate. */
    private fun downloadedJdk(target: Target, feature: Int): JdkHome {
        val sources = JdkSources.from(project.providers)
        val yum = sources.yum
        val jdk = if (yum != null) {
            extracted(target, yumPackage(target, feature, yum), rpm = true)
        } else {
            extracted(target, resolved("jdk", target) { sources.artifact(it, target) }, sources.archivesAreRpms)
        }
        // A YUM package is a whole JDK, jmods/ included.
        if (yum != null || !sources.distribution.separateJmods(feature)) return jdk
        val archive = resolved("jmods", target) { sources.jmodsArtifact(it, target) }
        val jmods = extract("extractJmods${target.taskSuffix}", archive, "jdk/${target.id}-jmods")
        return JdkHome(jdk.dir, jdk.builtBy, project.layout.dir(jmods.map { it.destinationDir }), jmods)
    }

    /** The JDK's RPM from a YUM repository, found by package name, pinned version and [target]'s CPU. */
    private fun yumPackage(target: Target, feature: Int, yum: JdkYumSource): Provider<File> {
        val task = project.tasks.register("downloadJdk${target.taskSuffix}", DownloadYumPackage::class.java) {
            group = wiring.taskGroup
            description = "Downloads the ${target.id} JDK package from the YUM repository ${yum.repository}"
            repositoryUrl.set(yum.repository.toString())
            packageName.set(yum.packageName(feature))
            version.set(extension.jdkVersion.map { yum.version(JdkVersion(it)) })
            arch.set(target.arch.rpm)
            wiring.binaryRepo?.let { login.set(it.login(project.providers)) }
            cacheDir.set(project.gradle.gradleUserHomeDir.resolve("caches/embedded-runtime/yum"))
            rpm.set(wiring.workDir.map { it.file("jdk/${target.id}.rpm") })
        }
        return task.flatMap { it.rpm }.map { it.asFile }
    }

    private fun resolved(name: String, target: Target, artifact: (JdkVersion) -> RuntimeArtifact): Provider<File> {
        val configuration =
            wiring.runtimeArchive("$name${target.taskSuffix}", extension.jdkVersion.map { artifact(JdkVersion(it)) })
        return wiring.singleFile(configuration)
    }

    private fun extracted(target: Target, archive: Provider<File>, rpm: Boolean): JdkHome {
        val name = "extractJdk${target.taskSuffix}"
        val destination = wiring.workDir.map { it.dir("jdk/${target.id}") }
        if (rpm) {
            val task = project.tasks.register(name, ExtractRpmRuntime::class.java) {
                group = wiring.taskGroup
                description = "Extracts the ${target.id} JDK from its RPM"
                this.rpm.fileProvider(archive)
                marker.set("release")
                outputDir.set(destination)
            }
            return JdkHome(task.flatMap { it.outputDir }, task)
        }
        val task = extract(name, archive, "jdk/${target.id}")
        return JdkHome(project.layout.dir(task.map { it.destinationDir }), task)
    }

    private fun extract(name: String, archive: Provider<File>, path: String) =
        wiring.extractRuntime(name, archive, wiring.workDir.map { it.dir(path) })
}
