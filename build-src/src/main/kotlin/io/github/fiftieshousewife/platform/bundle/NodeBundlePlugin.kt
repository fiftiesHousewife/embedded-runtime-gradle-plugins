package io.github.fiftieshousewife.platform.bundle

import io.github.fiftieshousewife.platform.PlatformProperties
import io.github.fiftieshousewife.platform.RepositoryMode
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateLaunchers.Companion.cmdQuote
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateLaunchers.Companion.shQuote
import io.github.fiftieshousewife.platform.bundle.tasks.GenerateNpmrc
import io.github.fiftieshousewife.platform.bundle.tasks.NodeLayout
import io.github.fiftieshousewife.platform.bundle.tasks.NpmProductionInstall
import io.github.fiftieshousewife.platform.bundle.tasks.NpmTask
import io.github.fiftieshousewife.platform.bundle.tasks.VerifyNodeModules
import io.github.fiftieshousewife.platform.runtime.Os
import io.github.fiftieshousewife.platform.runtime.RuntimeCatalog
import io.github.fiftieshousewife.platform.runtime.RuntimeCoordinates
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject

/**
 * `io.github.fiftieshousewife.platform.node-bundle` — a Node.js application packaged with the official Node
 * binary into a self-contained `.tar.gz`. Gradle drives npm with the same pinned Node distribution
 * that is bundled, so nothing needs to be installed on the build machine except a JDK for Gradle.
 *
 * Bundle layout: `bin/<app>`, `runtime/bin/node` (`runtime/node.exe` on Windows),
 * `app/` (sources, `package.json`, production `node_modules`), `conf.defaults/`, `META-INF/`.
 * Dependencies live in `app/node_modules` rather than `lib/` because ES modules resolve packages
 * by walking up from the importing file and ignore `NODE_PATH`.
 */
abstract class NodeBundlePlugin
    @Inject
    constructor(
        private val archives: ArchiveOperations,
        private val components: SoftwareComponentFactory,
    ) : Plugin<Project> {
        override fun apply(project: Project) {
            project.pluginManager.apply("base")
            val extension = project.extensions.create("bundle", NodeBundleExtension::class.java)
            val wiring = BundleWiring(project, extension, archives, components)
            wiring.applyConventions()
            applyConventions(project, extension)

            val (extractTarget, extractHost) = registerRuntimeArchives(extension, wiring)
            val node =
                PinnedNode(
                    home = project.layout.dir(extractHost.map { it.destinationDir }),
                    version = extension.nodeVersion,
                    extracted = extractHost,
                    npmrc = registerNpmrc(project, wiring),
                )
            val npmBuild = registerNpmTasks(project, extension, node)
            val productionInstall = registerProductionInstall(project, extension, wiring, node)
            val prodModules = productionInstall.flatMap { it.outputDir }.map { it.dir("node_modules") }

            val verifyModules =
                project.tasks.register("bundleVerifyNodeModules", VerifyNodeModules::class.java) {
                    group = BundleWiring.VERIFICATION_GROUP
                    description = "Checks production node_modules for host-specific install-script output"
                    dependsOn(productionInstall)
                    nodeModules.set(prodModules)
                    crossBuild.set(wiring.cross)
                    ignoredScripts.set(extension.ignoreInstallScripts)
                    portable.set(extension.portableInstallScripts)
                    reportFile.set(wiring.workDir.map { it.file("node-modules-report.txt") })
                }

            val runtime =
                project.tasks.register("bundleNodeRuntime", Sync::class.java) {
                    group = BundleWiring.BUILD_GROUP
                    description = "Selects the node binary and licence from the target distribution"
                    // Same platform: the build-time distribution is also the runtime, extracted once.
                    val source = wiring.cross.flatMap { cross -> if (cross) extractTarget else extractHost }
                    dependsOn(source)
                    from(project.layout.dir(source.map { it.destinationDir })) {
                        include("bin/node", "node.exe", "LICENSE")
                    }
                    into(wiring.workDir.zip(wiring.target) { d, p -> d.dir("node-runtime/${p.id}") })
                }

            val packageJsonFile = project.layout.projectDirectory.file("package.json")
            wiring.register(
                ecosystemSpec(extension, prodModules) {
                    into("runtime") { from(runtime) }
                    dependsOn(npmBuild, verifyModules)
                    into("app") {
                        from(extension.appFiles)
                        from(packageJsonFile)
                    }
                    into("app/node_modules") {
                        from(prodModules) { exclude(".bin/**", ".package-lock.json") }
                    }
                },
            )
        }

        private fun applyConventions(
            project: Project,
            extension: NodeBundleExtension,
        ) {
            val projectDir = project.layout.projectDirectory
            extension.nodeVersion.convention(RuntimeCatalog.nodeVersion(project.providers))
            // src/bundle/ holds conf.defaults and the Dockerfile override, so it is not application code.
            extension.appFiles.convention(
                extension.buildScript
                    .map { projectDir.dir("dist").asFileTree }
                    .orElse(projectDir.dir("src").asFileTree.matching { exclude("bundle/**") }),
            )
            extension.entryPoint.convention("main.js")
            extension.testScript.convention("test")
            extension.ignoreInstallScripts.convention(true)
            extension.nodeArgs.convention(emptyList())
            extension.portableInstallScripts.convention(emptyList())
        }

        /** The target and build-host Node.js distributions, each extracted; the same task twice for a host build. */
        private fun registerRuntimeArchives(
            extension: NodeBundleExtension,
            wiring: BundleWiring,
        ): Pair<TaskProvider<Sync>, TaskProvider<Sync>> {
            val targetNodeArchive =
                wiring.runtimeArchive(
                    "bundleTargetNode",
                    extension.nodeVersion.zip(wiring.target) { v, p -> RuntimeCoordinates.node(wiring.repos, v, p) },
                )
            val hostNodeArchive =
                wiring.runtimeArchive(
                    "bundleHostNode",
                    extension.nodeVersion.map { v -> RuntimeCoordinates.node(wiring.repos, v, wiring.host) },
                )
            val extractTarget =
                wiring.extractRuntime(
                    "bundleExtractTargetNode",
                    targetNodeArchive,
                    wiring.workDir.zip(wiring.target) { d, p -> d.dir("node/${p.id}") },
                )
            val extractHost =
                wiring.extractRuntime(
                    "bundleExtractHostNode",
                    hostNodeArchive,
                    wiring.workDir.map { it.dir("node/${wiring.host.id}") },
                )
            return extractTarget to extractHost
        }

        private fun registerNpmrc(
            project: Project,
            wiring: BundleWiring,
        ) = project.tasks.register("bundleNpmrc", GenerateNpmrc::class.java) {
            group = BundleWiring.BUILD_GROUP
            description = "Writes the npm user config for the platform npm registry"
            registry.set(wiring.repos.npmRegistry.toString())
            caFile.set(project.providers.gradleProperty(PlatformProperties.TLS_CA_FILE))
            if (wiring.repos.mode == RepositoryMode.ARTIFACTORY) {
                credentials.set(project.providers.credentials(PasswordCredentials::class.java, PlatformProperties.CREDENTIALS_NAME))
            }
            outputFile.set(wiring.workDir.map { it.file("npm/npmrc") })
        }

        /** `npmInstall`, `npmBuild` and `npmTest` over the working tree; returns `npmBuild`. */
        private fun registerNpmTasks(
            project: Project,
            extension: NodeBundleExtension,
            node: PinnedNode,
        ): TaskProvider<NpmTask> {
            val tasks = project.tasks
            val projectDir = project.layout.projectDirectory
            val packageJsonFile = projectDir.file("package.json")

            val npmInstall =
                tasks.register("npmInstall", NpmTask::class.java) {
                    group = BundleWiring.BUILD_GROUP
                    description = "npm ci — installs all dependencies for building and testing"
                    use(node)
                    workingDirectory.set(projectDir)
                    npmArgs.set(listOf("ci"))
                    inputs.files(packageJsonFile, projectDir.file("package-lock.json")).withPropertyName("packageFiles")
                    outputs.dir(projectDir.dir("node_modules")).withPropertyName("nodeModules")
                }

            val npmBuild =
                tasks.register("npmBuild", NpmTask::class.java) {
                    group = BundleWiring.BUILD_GROUP
                    description = "Runs the package.json script named by bundle.buildScript, if set"
                    use(node)
                    dependsOn(npmInstall)
                    workingDirectory.set(projectDir)
                    npmArgs.set(extension.buildScript.map { listOf("run", it) }.orElse(emptyList()))
                    val script = extension.buildScript
                    onlyIf("bundle.buildScript is set") { script.isPresent }
                }

            val npmTest =
                tasks.register("npmTest", NpmTask::class.java) {
                    group = BundleWiring.VERIFICATION_GROUP
                    description = "Runs the package.json test script, if present"
                    use(node)
                    dependsOn(npmInstall, npmBuild)
                    workingDirectory.set(projectDir)
                    npmArgs.set(extension.testScript.map { listOf("run", it) })
                    val manifest = packageJsonFile.asFile
                    val script = extension.testScript
                    onlyIf("package.json defines the test script") { NodeLayout.hasScript(manifest, script.get()) }
                }
            tasks.named("check") { dependsOn(npmTest) }
            return npmBuild
        }

        private fun registerProductionInstall(
            project: Project,
            extension: NodeBundleExtension,
            wiring: BundleWiring,
            node: PinnedNode,
        ) = project.tasks.register("npmInstallProduction", NpmProductionInstall::class.java) {
            group = BundleWiring.BUILD_GROUP
            description = "npm ci --omit=dev for the target platform, isolated from the working tree"
            dependsOn(node.extracted, node.npmrc)
            nodeHome.set(node.home)
            nodeVersion.set(node.version)
            npmrc.set(node.npmrc.flatMap { it.outputFile })
            packageJson.set(project.layout.projectDirectory.file("package.json"))
            packageLock.set(project.layout.projectDirectory.file("package-lock.json"))
            val host = wiring.host
            npmArgs.set(
                extension.ignoreInstallScripts.zip(wiring.target) { ignore, target ->
                    val args = mutableListOf<String>()
                    if (ignore) args += "--ignore-scripts"
                    if (target != host) {
                        // Install the target's platform-specific optional dependencies (npm 10+).
                        args += "--os=${RuntimeCoordinates.npmOs(target)}"
                        args += "--cpu=${RuntimeCoordinates.npmCpu(target)}"
                        if (target.os == Os.LINUX) args += "--libc=glibc"
                    }
                    args
                },
            )
            outputDir.set(wiring.workDir.zip(wiring.target) { d, p -> d.dir("npm-production/${p.id}") })
        }

        private fun ecosystemSpec(
            extension: NodeBundleExtension,
            prodModules: Provider<Directory>,
            contents: Sync.() -> Unit,
        ) = EcosystemSpec(
            ecosystem = "node",
            runtimeName = "nodejs",
            runtimeVersion = extension.nodeVersion,
            posixExec =
                extension.entryPoint.zip(extension.nodeArgs) { entry, nodeArgs ->
                    listOf(
                        "exec \"\$APP_HOME/runtime/bin/node\"",
                        nodeArgs.joinToString(" ") { shQuote(it) },
                        "\${APP_NODE_OPTS:-}",
                        "\"\$APP_HOME/app/$entry\"",
                        "\"\$@\"",
                    ).filter(String::isNotEmpty).joinToString(" ")
                },
            windowsExec =
                extension.entryPoint.zip(extension.nodeArgs) { entry, nodeArgs ->
                    listOf(
                        "\"%APP_HOME%\\runtime\\node.exe\"",
                        nodeArgs.joinToString(" ") { cmdQuote(it) },
                        "%APP_NODE_OPTS%",
                        "\"%APP_HOME%\\app\\${entry.replace('/', '\\')}\"%ARGS%",
                    ).filter(String::isNotEmpty).joinToString(" ")
                },
            contents = contents,
            sbom = { nodeModules.set(prodModules) },
        )
    }

/** The build-host Node.js that drives npm, and the npm config it reads. */
private class PinnedNode(
    val home: Provider<Directory>,
    val version: Provider<String>,
    val extracted: TaskProvider<Sync>,
    val npmrc: TaskProvider<GenerateNpmrc>,
)

private fun NpmTask.use(node: PinnedNode) {
    nodeHome.set(node.home)
    nodeVersion.set(node.version)
    npmrc.set(node.npmrc.flatMap { it.outputFile })
    environment.convention(emptyMap())
    dependsOn(node.extracted, node.npmrc)
}
