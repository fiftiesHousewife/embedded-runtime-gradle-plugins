package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Ecosystem
import io.github.fiftieshousewife.appcore.EcosystemSpec
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.shQuote
import io.github.fiftieshousewife.javaapp.tasks.JdepsModules
import io.github.fiftieshousewife.javaapp.tasks.JlinkFindModules
import io.github.fiftieshousewife.javaapp.tasks.JlinkRuntime
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import javax.inject.Inject

/**
 * `io.github.fiftieshousewife.java-app` — a Java application packaged with a jlink-trimmed runtime
 * of the configured JDK distribution into a self-contained `.tar.gz` per target.
 *
 * Compilation and the embedded runtime use the same feature release and the same exact pin: the
 * toolchain resolver ([JavaAppSettingsPlugin]) and the runtime archives both come from [JdkCatalog]
 * and [JdkSources].
 *
 * The archive layout follows the `application` plugin's: `bin/<app>`, `lib/` (the application jar
 * and its dependencies), plus `runtime/` (jlink image), `conf.defaults/` and
 * `META-INF/{app.json,sbom.cdx.json}`.
 *
 * The runtime's modules default to what `jdeps` finds plus `additionalModules`; `jlinkFindModules`
 * finds the ones jdeps cannot see by running the app.
 */
abstract class JavaAppPlugin
    @Inject
    constructor(
        private val archives: ArchiveOperations,
        private val components: SoftwareComponentFactory,
    ) : Plugin<Project> {
        override fun apply(project: Project) {
            project.pluginManager.apply("application")
            val extension = project.extensions.create(ECOSYSTEM.extensionName, JavaAppExtension::class.java)
            val wiring = AppWiring(project, ECOSYSTEM, extension, archives, components)
            wiring.applyConventions()
            val detectedModules = project.objects.listProperty(String::class.java)
            applyConventions(project, extension, detectedModules)

            // The archive replaces the application plugin's script-based distributions; keeping both
            // would publish two different ways to run the same app. installDist remains available.
            listOf("distTar", "distZip").forEach { name -> project.tasks.named(name) { enabled = false } }

            // Targets and the JDK release are final once the build script has run; JDKs are set up
            // then, before the shared pipeline asks for each target's contribution.
            val jdks = Jdks(project, extension, wiring)
            project.afterEvaluate {
                val hostJdk = jdks.forTarget(wiring.host)
                val jdeps = registerJdeps(project, extension, hostJdk)
                detectedModules.set(jdeps.flatMap { it.outputFile }.map { JdepsModules.read(it.asFile) })
                registerFindModules(project, extension, wiring, hostJdk)
            }
            wiring.register { target ->
                ecosystemSpec(project, extension, registerJlink(project, extension, wiring, jdks, target))
            }
            project.tasks.named("appSmokeTest", AppSmokeTest::class.java) {
                failureHint.set("If the app is missing a JDK module, ./gradlew jlinkFindModules finds which.")
            }
        }

        private fun applyConventions(
            project: Project,
            extension: JavaAppExtension,
            detectedModules: Provider<List<String>>,
        ) {
            val sources = JdkSources.from(project.providers)
            extension.javaVersion.convention(JdkCatalog.defaultJavaFeature)
            // Every use goes through this, so an unsupported release is reported once, by name, rather
            // than as a missing pin or a jlink failure further down.
            val javaFeature = extension.javaVersion.map(JdkCatalog::requireSupported)
            extension.jdkVersion.convention(JdkCatalog.jdkVersion(project.providers, sources.distribution, javaFeature))
            extension.additionalModules.convention(emptyList())
            extension.jlinkModules.convention(
                detectedModules.zip(extension.additionalModules) { detected, extra -> (detected + extra).distinct() },
            )
            extension.jlinkOptions.convention(
                javaFeature.map { feature ->
                    listOf("--strip-debug", "--no-man-pages", "--no-header-files") +
                        if (feature >= JdkCatalog.NAMED_COMPRESSION_FROM) "--compress=zip-6" else "--compress=2"
                },
            )
            extension.jvmArgs.convention(emptyList())

            project.extensions.getByType(JavaPluginExtension::class.java).toolchain {
                languageVersion.convention(javaFeature.map(JavaLanguageVersion::of))
                vendor.convention(sources.distribution.vendorSpec)
            }
        }

        /** Links the trimmed runtime for [target] with this machine's jlink, from [target]'s JDK. */
        private fun registerJlink(
            project: Project,
            extension: JavaAppExtension,
            wiring: AppWiring,
            jdks: Jdks,
            target: Target,
        ): TaskProvider<JlinkRuntime> {
            val hostHome = jdks.forTarget(wiring.host)
            val targetHome = jdks.forTarget(target)
            return project.tasks.register("jlink${target.taskSuffix}", JlinkRuntime::class.java) {
                group = ECOSYSTEM.taskGroup
                description = "Links the trimmed Java runtime for ${target.id}"
                toolJdk.set(hostHome.dir)
                targetJdk.set(targetHome.dir)
                dependsOn(listOfNotNull(hostHome.builtBy, targetHome.builtBy))
                jdkVersion.set(extension.jdkVersion)
                host.set(wiring.host)
                this.target.set(target)
                modules.set(extension.jlinkModules)
                options.set(extension.jlinkOptions)
                outputDir.set(wiring.workDir.map { it.dir("jlink/${target.id}") })
            }
        }

        private fun registerJdeps(
            project: Project,
            extension: JavaAppExtension,
            hostJdk: JdkHome,
        ) = project.tasks.register("jdeps", JdepsModules::class.java) {
            group = ECOSYSTEM.taskGroup
            description = "Finds the JDK modules the app and its dependencies use, with jdeps"
            jdk.set(hostJdk.dir)
            hostJdk.builtBy?.let { dependsOn(it) }
            jdkVersion.set(extension.jdkVersion)
            javaFeature.set(extension.javaVersion)
            appJar.from(project.tasks.named("jar", Jar::class.java))
            classpath.from(project.configurations.named("runtimeClasspath"))
            outputFile.set(project.layout.buildDirectory.file("app-work/jdeps/modules.txt"))
        }

        private fun registerFindModules(
            project: Project,
            extension: JavaAppExtension,
            wiring: AppWiring,
            hostJdk: JdkHome,
        ) {
            val assembleName = "appAssemble${wiring.host.taskSuffix}"
            project.tasks.register("jlinkFindModules", JlinkFindModules::class.java) {
                group = ECOSYSTEM.taskGroup
                description = "Runs the app, adding each JDK module it turns out to need, until it is healthy"
                jdk.set(hostJdk.dir)
                hostJdk.builtBy?.let { dependsOn(it) }
                if (assembleName in project.tasks.names) {
                    val assemble = project.tasks.named(assembleName, Sync::class.java)
                    dependsOn(assemble)
                    appDir.set(project.layout.dir(assemble.map { it.destinationDir }))
                } else {
                    val message = "jlinkFindModules runs the app, so javaApp.targets must include this machine (${wiring.host})"
                    doFirst { throw GradleException(message) }
                }
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

        private fun ecosystemSpec(
            project: Project,
            extension: JavaAppExtension,
            jlink: TaskProvider<JlinkRuntime>,
        ): EcosystemSpec {
            val jar = project.tasks.named("jar", Jar::class.java)
            val runtimeClasspath = project.configurations.named("runtimeClasspath")
            val mainClass = project.extensions.getByType(JavaApplication::class.java).mainClass
            val resolvedArtifacts = runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }

            // The application plugin puts every jar in lib/ and names the application's own jar
            // first on the classpath; the wildcard then picks up the dependencies. Naming it keeps
            // the application's classes winning over a duplicate in a dependency, which a bare
            // wildcard would leave to the JVM's directory order.
            val launch = mainClass.zip(jar.flatMap { it.archiveFileName }) { main, appJar -> main to appJar }

            return EcosystemSpec(
                runtimeName = JdkSources.from(project.providers).distribution.id,
                runtimeVersion = extension.jdkVersion,
                posixExec =
                    launch.zip(extension.jvmArgs) { (main, appJar), jvmArgs ->
                        listOf(
                            "exec \"\$APP_HOME/runtime/bin/java\"",
                            jvmArgs.joinToString(" ") { shQuote(it) },
                            "\${APP_JAVA_OPTS:-}",
                            "-cp \"\$APP_HOME/lib/$appJar:\$APP_HOME/lib/*\"",
                            shQuote(main),
                            "\"\$@\"",
                        ).filter(String::isNotEmpty).joinToString(" ")
                    },
                windowsExec =
                    launch.zip(extension.jvmArgs) { (main, appJar), jvmArgs ->
                        listOf(
                            "\"%APP_HOME%\\runtime\\bin\\java.exe\"",
                            jvmArgs.joinToString(" ") { cmdQuote(it) },
                            "%APP_JAVA_OPTS%",
                            "-cp \"%APP_HOME%\\lib\\$appJar;%APP_HOME%\\lib\\*\"",
                            main + "%ARGS%",
                        ).filter(String::isNotEmpty).joinToString(" ")
                    },
                contents = {
                    into("runtime") { from(jlink) }
                    into("lib") {
                        from(jar)
                        from(runtimeClasspath)
                    }
                },
                sbom = {
                    mavenComponents.set(
                        resolvedArtifacts.map { artifacts ->
                            artifacts.mapNotNull { a ->
                                (a.id.componentIdentifier as? ModuleComponentIdentifier)
                                    ?.let { "${it.group}:${it.module}:${it.version}:${a.file.name}" }
                            }
                        },
                    )
                    mavenFiles.from(resolvedArtifacts.map { artifacts -> artifacts.map { it.file } })
                },
            )
        }

        companion object {
            val ECOSYSTEM =
                Ecosystem(
                    id = "java",
                    extensionName = "javaApp",
                    settingsPluginId = JavaAppSettingsPlugin.ID,
                )

            private const val MAX_FIND_ATTEMPTS = 15
        }
    }

/** A JDK on disk: its directory, and the task that puts it there, if any. */
internal class JdkHome(
    val dir: Provider<Directory>,
    val builtBy: TaskProvider<Sync>?,
)

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
        val destination = wiring.workDir.map { it.dir("jdk/${target.id}") }
        val extractName = "extractJdk${target.taskSuffix}"
        return when {
            local == null -> {
                val sources = JdkSources.from(project.providers)
                val archive =
                    wiring.runtimeArchive(
                        "jdk${target.taskSuffix}",
                        extension.jdkVersion.map { sources.artifact(JdkVersion(it), target) },
                    )
                extracted(wiring.extractRuntime(extractName, wiring.singleFile(archive), destination))
            }

            local.isDirectory -> {
                JdkHome(project.layout.dir(project.provider { local }), builtBy = null)
            }

            local.isFile -> {
                extracted(wiring.extractRuntime(extractName, project.provider { local }, destination))
            }

            else -> {
                throw GradleException("${JdkSources.pathProperty(feature, target)} is $local, which does not exist")
            }
        }
    }

    private fun extracted(task: TaskProvider<Sync>) = JdkHome(project.layout.dir(task.map { it.destinationDir }), task)
}
