package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Ecosystem
import io.github.fiftieshousewife.appcore.EcosystemSpec
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.shQuote
import io.github.fiftieshousewife.javaapp.tasks.JlinkRuntime
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import javax.inject.Inject

/**
 * `io.github.fiftieshousewife.java-app` — a Java application packaged with a jlink-trimmed runtime
 * of the configured JDK distribution into a self-contained `.tar.gz`.
 *
 * Compilation and the bundled runtime use the same feature release and the same exact pin: the
 * toolchain resolver ([JavaAppSettingsPlugin]) and the runtime archive dependency both come from
 * [JdkCatalog] and [JdkSources].
 *
 * The archive layout follows the `application` plugin's: `bin/<app>`, `lib/` (the application jar and
 * its dependencies), plus `runtime/` (jlink image), `conf.defaults/` and
 * `META-INF/{app.json,sbom.cdx.json}`.
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
            applyConventions(project, extension)

            val jlink = registerJlink(project, extension, wiring)

            // The archive replaces the application plugin's script-based distributions; keeping both
            // would publish two different ways to run the same app. installDist remains available.
            listOf("distTar", "distZip").forEach { name -> project.tasks.named(name) { enabled = false } }

            wiring.register(ecosystemSpec(project, extension, jlink))
        }

        private fun applyConventions(
            project: Project,
            extension: JavaAppExtension,
        ) {
            val sources = JdkSources.from(project.providers)
            extension.javaVersion.convention(JdkCatalog.defaultJavaFeature)
            // Every use goes through this, so an unsupported release is reported once, by name, rather
            // than as a missing pin or a jlink failure further down.
            val javaFeature = extension.javaVersion.map(JdkCatalog::requireSupported)
            extension.jdkVersion.convention(JdkCatalog.jdkVersion(project.providers, sources.distribution, javaFeature))
            extension.jlinkModules.convention(listOf("java.base"))
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

        /** Resolves the target (and, for a cross build, host) JDK and links the app's runtime. */
        private fun registerJlink(
            project: Project,
            extension: JavaAppExtension,
            wiring: AppWiring,
        ): TaskProvider<JlinkRuntime> {
            val jdk = extension.jdkVersion.map(::JdkVersion)
            val sources = JdkSources.from(project.providers)
            val targetJdkArchive =
                wiring.runtimeArchive(
                    "targetJdk",
                    jdk.zip(wiring.target) { v, t -> sources.artifact(v, t) },
                )
            val hostJdkArchive =
                wiring.runtimeArchive(
                    "hostJdk",
                    jdk.map { v -> sources.artifact(v, wiring.host) },
                )
            val extractTarget =
                wiring.extractRuntime(
                    "extractTargetJdk",
                    targetJdkArchive,
                    wiring.workDir.zip(wiring.target) { d, t -> d.dir("jdk/${t.id}") },
                )
            val extractHost =
                wiring.extractRuntime(
                    "extractHostJdk",
                    hostJdkArchive,
                    wiring.workDir.map { it.dir("jdk/${wiring.host.id}") },
                )

            return project.tasks.register("jlink", JlinkRuntime::class.java) {
                group = ECOSYSTEM.taskGroup
                description = "Links the trimmed Java runtime for the target"
                // Same platform: one JDK serves as both jlink tool and jmods source.
                val toolTask = wiring.cross.flatMap { cross -> if (cross) extractHost else extractTarget }
                toolJdk.set(project.layout.dir(toolTask.map { it.destinationDir }))
                targetJdk.set(project.layout.dir(extractTarget.map { it.destinationDir }))
                dependsOn(extractTarget, toolTask)
                jdkVersion.set(extension.jdkVersion)
                host.set(wiring.host)
                target.set(wiring.target)
                modules.set(extension.jlinkModules)
                options.set(extension.jlinkOptions)
                outputDir.set(wiring.workDir.zip(wiring.target) { d, t -> d.dir("jlink/${t.id}") })
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
        }
    }
