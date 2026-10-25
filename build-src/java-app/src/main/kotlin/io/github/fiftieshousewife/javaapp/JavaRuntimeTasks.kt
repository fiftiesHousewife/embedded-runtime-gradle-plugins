package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.javaapp.tasks.CustomiseRuntime
import io.github.fiftieshousewife.javaapp.tasks.JdepsModules
import io.github.fiftieshousewife.javaapp.tasks.JlinkFindModules
import io.github.fiftieshousewife.javaapp.tasks.JlinkRuntime
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar

/** The Java-specific tasks: per target, jlink and customisation; for this machine, jdeps and module finding. */
internal class JavaRuntimeTasks(
    private val project: Project,
    private val extension: JavaAppExtension,
    private val wiring: AppWiring,
) {
    private val jdks = Jdks(project, extension, wiring)

    /**
     * This machine's JDK, which runs jdeps and jlink. Each task asks for it before it is registered:
     * setting it up registers its download tasks, which Gradle forbids while a task is being created.
     */
    private val hostJdk get() = jdks.forTarget(wiring.host)
    private val group = JavaAppPlugin.ECOSYSTEM.taskGroup

    /** Links [target]'s trimmed runtime with this machine's jlink, from [target]'s JDK. */
    fun jlink(target: Target): TaskProvider<JlinkRuntime> {
        val targetHome = jdks.forTarget(target)
        val toolHome = hostJdk
        return project.tasks.register("jlink${target.taskSuffix}", JlinkRuntime::class.java) {
            group = this@JavaRuntimeTasks.group
            description = "Links the trimmed Java runtime for ${target.id}"
            toolJdk.set(toolHome.dir)
            targetJdk.set(targetHome.dir)
            targetHome.jmods?.let(targetJmods::set)
            dependsOn(toolHome.tasks + targetHome.tasks)
            jdkVersion.set(extension.jdkVersion)
            host.set(wiring.host)
            this.target.set(target)
            modules.set(extension.jlinkModules)
            options.set(extension.jlinkOptions)
            outputDir.set(wiring.workDir.map { it.dir("jlink/${target.id}") })
        }
    }

    /** Applies `javaApp.security` and the runtime overlay to the runtime [jlink] links. */
    fun customise(target: Target, jlink: TaskProvider<JlinkRuntime>) =
        project.tasks.register("customiseRuntime${target.taskSuffix}", CustomiseRuntime::class.java) {
            group = this@JavaRuntimeTasks.group
            description = "Applies approved certificates, security policy and the overlay to the ${target.id} runtime"
            val security = extension.security
            runtime.set(jlink.flatMap { it.outputDir })
            trustedCertificates.from(security.trustedCertificates)
            trustedCertificateUris.set(security.trustedCertificateUris)
            replaceDefaultTrust.set(security.replaceDefaultTrust)
            propertiesFiles.from(security.propertiesFiles)
            properties.set(security.properties)
            appendToLists.put("jdk.tls.disabledAlgorithms", security.disabledTlsAlgorithms)
            appendToLists.put("jdk.certpath.disabledAlgorithms", security.disabledCertPathAlgorithms)
            appendToLists.put("jdk.jar.disabledAlgorithms", security.disabledJarAlgorithms)
            lockProperties.set(security.lockProperties)
            forbiddenModules.set(security.forbiddenModules)
            policy.from(wiring.runtimePolicy)
            overlay.set(extension.runtimeOverlay)
            outputDir.set(wiring.workDir.map { it.dir("runtime/${target.id}") })
        }

    fun jdeps(): TaskProvider<JdepsModules> {
        val home = hostJdk
        return project.tasks.register("jdeps", JdepsModules::class.java) {
            group = this@JavaRuntimeTasks.group
            description = "Finds the JDK modules the app and its dependencies use, with jdeps"
            jdk.set(home.dir)
            home.builtBy?.let { dependsOn(it) }
            jdkVersion.set(extension.jdkVersion)
            javaFeature.set(extension.javaVersion)
            appJar.from(project.tasks.named("jar", Jar::class.java))
            classpath.from(project.configurations.named("runtimeClasspath"))
            outputFile.set(project.layout.buildDirectory.file("app-work/jdeps/modules.txt"))
        }
    }

    fun findModules() {
        val home = hostJdk
        project.tasks.register("jlinkFindModules", JlinkFindModules::class.java) {
            group = this@JavaRuntimeTasks.group
            description = "Runs the app, adding each JDK module it turns out to need, until it is healthy"
            jdk.set(home.dir)
            home.jmods?.let(jmods::set)
            dependsOn(home.tasks)
            useThisMachinesApp(this)
            appName.set(extension.appName)
            expectedVersion.set(project.provider { project.version.toString() })
            windows.set(wiring.host.isWindows)
            modules.set(extension.jlinkModules)
            options.set(extension.jlinkOptions)
            healthTimeout.set(extension.smokeTestTimeout)
            maxAttempts.set(MAX_FIND_ATTEMPTS)
            reportFile.set(project.layout.buildDirectory.file("app-work/jlink-find-modules.txt"))
        }
    }

    /** The module search runs this machine's assembled app, so it needs a target for this machine. */
    private fun useThisMachinesApp(task: JlinkFindModules) {
        val assembleName = wiring.host.installTaskName
        if (assembleName !in project.tasks.names) {
            val message = "jlinkFindModules runs the app, so javaApp.targets must include this machine (${wiring.host})"
            task.doFirst { throw GradleException(message) }
            return
        }
        val assemble = project.tasks.named(assembleName, Sync::class.java)
        task.dependsOn(assemble)
        task.appDir.set(project.layout.dir(assemble.map { it.destinationDir }))
    }

    private companion object {
        const val MAX_FIND_ATTEMPTS = 15
    }
}
