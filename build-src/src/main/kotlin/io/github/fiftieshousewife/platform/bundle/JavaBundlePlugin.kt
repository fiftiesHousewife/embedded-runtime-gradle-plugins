package io.github.fiftieshousewife.platform.bundle

import io.github.fiftieshousewife.platform.bundle.tasks.GenerateLaunchers.Companion.cmdQuote
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateLaunchers.Companion.shQuote
import io.github.fiftieshousewife.platform.bundle.tasks.JlinkRuntime
import io.github.fiftieshousewife.platform.runtime.JdkVersion
import io.github.fiftieshousewife.platform.runtime.RuntimeCatalog
import io.github.fiftieshousewife.platform.runtime.RuntimeCoordinates
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
 * `io.github.fiftieshousewife.platform.java-bundle` — a Java application packaged with a
 * jlink-trimmed runtime of the configured JDK distribution into a self-contained `.tar.gz`.
 *
 * Compilation and the bundled runtime use the same feature release and the same exact pin:
 * the toolchain resolver (settings plugin) and the runtime archive dependency both come from
 * [RuntimeCatalog].
 *
 * Bundle layout follows the `application` plugin's: `bin/<app>`, `lib/` (the application jar and
 * its dependencies), plus `runtime/` (jlink image), `conf.defaults/` and
 * `META-INF/{bundle.json,sbom.cdx.json}`.
 */
abstract class JavaBundlePlugin
    @Inject
    constructor(
        private val archives: ArchiveOperations,
        private val components: SoftwareComponentFactory,
    ) : Plugin<Project> {
        override fun apply(project: Project) {
            project.pluginManager.apply("application")
            val extension = project.extensions.create("bundle", JavaBundleExtension::class.java)
            val wiring = BundleWiring(project, extension, archives, components)
            wiring.applyConventions()
            applyConventions(project, extension, wiring)

            val jlink = registerJlink(project, extension, wiring)

            // The bundle replaces the application plugin's script-based distributions; keeping both
            // would publish two different ways to run the same app. installDist remains available.
            listOf("distTar", "distZip").forEach { name -> project.tasks.named(name) { enabled = false } }

            wiring.register(ecosystemSpec(project, extension, wiring, jlink))
        }

        private fun applyConventions(
            project: Project,
            extension: JavaBundleExtension,
            wiring: BundleWiring,
        ) {
            extension.javaVersion.convention(RuntimeCatalog.defaultJavaFeature)
            // Every use goes through this, so an unsupported release is reported once, by name, rather
            // than as a missing pin or a jlink failure further down.
            val javaFeature = extension.javaVersion.map(RuntimeCatalog::requireSupported)
            extension.jdkVersion.convention(RuntimeCatalog.jdkVersion(project.providers, wiring.repos.jdkDistribution, javaFeature))
            extension.jlinkModules.convention(listOf("java.base"))
            extension.jlinkOptions.convention(
                javaFeature.map { feature ->
                    listOf("--strip-debug", "--no-man-pages", "--no-header-files") +
                        if (feature >= RuntimeCatalog.NAMED_COMPRESSION_FROM) "--compress=zip-6" else "--compress=2"
                },
            )
            extension.jvmArgs.convention(emptyList())

            project.extensions.getByType(JavaPluginExtension::class.java).toolchain {
                languageVersion.convention(javaFeature.map(JavaLanguageVersion::of))
                vendor.convention(wiring.repos.jdkDistribution.vendorSpec)
            }
        }

        /** Resolves the target (and, for a cross build, host) JDK and links the bundled runtime. */
        private fun registerJlink(
            project: Project,
            extension: JavaBundleExtension,
            wiring: BundleWiring,
        ): TaskProvider<JlinkRuntime> {
            val jdk = extension.jdkVersion.map(::JdkVersion)
            val targetJdkArchive =
                wiring.runtimeArchive(
                    "bundleTargetJdk",
                    jdk.zip(wiring.target) { v, p -> RuntimeCoordinates.jdk(wiring.repos, v, p) },
                )
            val hostJdkArchive =
                wiring.runtimeArchive(
                    "bundleHostJdk",
                    jdk.map { v -> RuntimeCoordinates.jdk(wiring.repos, v, wiring.host) },
                )
            val extractTarget =
                wiring.extractRuntime(
                    "bundleExtractTargetJdk",
                    targetJdkArchive,
                    wiring.workDir.zip(wiring.target) { d, p -> d.dir("jdk/${p.id}") },
                )
            val extractHost =
                wiring.extractRuntime(
                    "bundleExtractHostJdk",
                    hostJdkArchive,
                    wiring.workDir.map { it.dir("jdk/${wiring.host.id}") },
                )

            return project.tasks.register("bundleJlink", JlinkRuntime::class.java) {
                group = BundleWiring.BUILD_GROUP
                description = "Links the trimmed Java runtime for the target platform"
                // Same platform: one JDK serves as both jlink tool and jmods source.
                val toolTask = wiring.cross.flatMap { cross -> if (cross) extractHost else extractTarget }
                toolJdk.set(project.layout.dir(toolTask.map { it.destinationDir }))
                targetJdk.set(project.layout.dir(extractTarget.map { it.destinationDir }))
                dependsOn(extractTarget, toolTask)
                jdkVersion.set(extension.jdkVersion)
                hostPlatform.set(wiring.host)
                targetPlatform.set(wiring.target)
                modules.set(extension.jlinkModules)
                options.set(extension.jlinkOptions)
                outputDir.set(wiring.workDir.zip(wiring.target) { d, p -> d.dir("jlink/${p.id}") })
            }
        }

        private fun ecosystemSpec(
            project: Project,
            extension: JavaBundleExtension,
            wiring: BundleWiring,
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
                ecosystem = "java",
                runtimeName = wiring.repos.jdkDistribution.id,
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
    }
