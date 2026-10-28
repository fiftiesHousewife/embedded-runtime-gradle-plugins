package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.RuntimeArtifact
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.setting
import io.github.fiftieshousewife.appcore.tasks.DownloadYumPackage
import io.github.fiftieshousewife.appcore.tasks.ExtractRpmRuntime
import io.github.fiftieshousewife.javaapp.tasks.JdkTools
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File

/** [jmods] is set when the vendor publishes them separately ([JdkDistribution.separateJmods]). */
internal class JdkHome(
    val dir: Provider<Directory>,
    val builtBy: TaskProvider<out Task>?,
    val jmods: Provider<Directory>? = null,
    private val jmodsBuiltBy: TaskProvider<out Task>? = null,
) {
    val tasks: List<TaskProvider<out Task>> get() = listOfNotNull(builtBy, jmodsBuiltBy)
}

/**
 * A directory configured by path is used as it is, an archive extracted, else the pin is resolved from [JdkSources].
 * Called once the build script has run, when the Java release is final.
 */
internal class Jdks(
    private val project: Project,
    private val extension: JavaAppExtension,
    private val jdkVersion: Provider<String>,
    private val wiring: AppWiring,
) {
    private val homes = mutableMapOf<Target, JdkHome>()

    fun forTarget(target: Target): JdkHome = homes.getOrPut(target) { setUp(target) }

    private fun setUp(target: Target): JdkHome {
        val feature = JdkCatalog.requireSupported(extension.javaVersion.get())
        val local = JdkSources.localJdk(project.providers, feature, target)
        return when {
            local == null -> toolchainJdk(target, feature) ?: downloadedJdk(target, feature)

            local.isDirectory -> JdkHome(project.layout.dir(project.provider { local }), builtBy = null)

            local.isFile -> extracted(target, project.provider { local }, rpm = local.name.endsWith(".rpm", true))

            else -> throw GradleException(
                "${JdkSources.pathProperty(feature, target)} is $local, which does not exist. In gradle.properties " +
                    "a backslash escapes the next character: write Windows paths as C:/Program Files/Java/jdk-21.",
            )
        }
    }

    /** Nothing to download or unpack; only a vendor's separate jmods are still fetched. */
    private fun toolchainJdk(target: Target, feature: Int): JdkHome? {
        val sources = JdkSources.from(project.providers)
        if (whyNotToolchainJdk(target, sources.distribution, feature) != null) return null
        val home = toolchainHome.getOrThrow()
        project.logger.info("Using the compile toolchain's JDK for ${target.id}: $home")
        val dir = project.layout.dir(project.provider { home })
        if (home.resolve(JMODS).isDirectory) return JdkHome(dir, builtBy = null)
        val jmods =
            extract("extractJmods${target.taskSuffix}", jmodsArchive(sources, target), "jdk/${target.id}-$JMODS")
        return JdkHome(dir, builtBy = null, project.layout.dir(jmods.map { it.destinationDir }), jmods)
    }

    /** The JDK Gradle compiles with, not the one running the daemon, which is often another. */
    private val toolchainHome: Result<File> by lazy {
        runCatching {
            val toolchain = project.extensions.getByType(JavaPluginExtension::class.java).toolchain
            project.extensions.getByType(JavaToolchainService::class.java)
                .compilerFor(toolchain).get().metadata.installationPath.asFile
        }
    }

    /** Null when the compile toolchain's JDK can be [target]'s. */
    private fun whyNotToolchainJdk(target: Target, distribution: JdkDistribution, feature: Int): String? {
        if (project.providers.setting(JdkSources.REUSE_TOOLCHAIN)?.toBoolean() == false) {
            return "reusing it is turned off (${JdkSources.REUSE_TOOLCHAIN}=false)"
        }
        if (target != wiring.host) return "it runs on ${wiring.host.id}, not ${target.id}"
        val home = toolchainHome.getOrElse { return "Gradle could not resolve it: ${it.message}" }
        val pin = jdkVersion.get()
        val implementor = JdkTools.implementor(home)
        return when {
            implementor != distribution.implementor || !JdkTools.isPinned(pin, home) ->
                "it is $implementor ${JdkTools.javaVersion(home)} at $home, not ${distribution.implementor} $pin"

            !home.resolve(JMODS).isDirectory && !distribution.separateJmods(feature) ->
                "it has no jmods/ to link from ($home)"

            else -> null
        }
    }

    private fun jmodsArchive(sources: JdkSources, target: Target) =
        resolved(JMODS, target) { sources.jmodsArtifact(it, target) }

    private fun downloadedJdk(target: Target, feature: Int): JdkHome {
        val sources = JdkSources.from(project.providers)
        if (sources.archivesAreRpms && target.os != Os.LINUX) throw linuxOnly(target, feature, sources)
        val yum = sources.yum
        val jdk = if (yum != null) {
            extracted(target, yumPackage(target, feature, yum), rpm = true)
        } else {
            extracted(target, resolved("jdk", target) { sources.artifact(it, target) }, sources.archivesAreRpms)
        }
        // A YUM package is a whole JDK, jmods/ included.
        if (yum != null || !sources.distribution.separateJmods(feature)) return jdk
        val jmods =
            extract("extractJmods${target.taskSuffix}", jmodsArchive(sources, target), "jdk/${target.id}-$JMODS")
        return JdkHome(jdk.dir, jdk.builtBy, project.layout.dir(jmods.map { it.destinationDir }), jmods)
    }

    /** A YUM repository or `.rpm` pattern holds Linux JDKs only; this says why the toolchain's wasn't used. */
    private fun linuxOnly(target: Target, feature: Int, sources: JdkSources): GradleException {
        val source = if (sources.yum != null) JdkSources.YUM_REPO else "the .rpm pattern"
        return GradleException(
            "No JDK for ${target.id}: $source serves Linux JDKs only. The compile toolchain's JDK can't be used: " +
                "${whyNotToolchainJdk(target, sources.distribution, feature)}. Install the pinned JDK where Gradle " +
                "finds toolchains, or set ${JdkSources.pathProperty(feature, target)} to a ${target.os.id} JDK.",
        )
    }

    private fun yumPackage(target: Target, feature: Int, yum: JdkYumSource): Provider<File> {
        val task = project.tasks.register("downloadJdk${target.taskSuffix}", DownloadYumPackage::class.java) {
            group = wiring.taskGroup
            description = "Downloads the ${target.id} JDK package from the YUM repository ${yum.repository}"
            repositoryUrl.set(yum.repository.toString())
            packageName.set(yum.packageName(feature))
            version.set(jdkVersion.map { yum.version(JdkVersion(it)) })
            arch.set(target.arch.rpm)
            wiring.binaryRepo?.let { login.set(it.login(project.providers)) }
            cacheDir.set(project.gradle.gradleUserHomeDir.resolve("caches/embedded-runtime/yum"))
            rpm.set(wiring.workDir.map { it.file("jdk/${target.id}.rpm") })
        }
        return task.flatMap { it.rpm }.map { it.asFile }
    }

    private fun resolved(name: String, target: Target, artifact: (JdkVersion) -> RuntimeArtifact): Provider<File> {
        val configuration =
            wiring.runtimeArchive("$name${target.taskSuffix}", jdkVersion.map { artifact(JdkVersion(it)) })
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

    private companion object {
        const val JMODS = "jmods"
    }
}
