package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Artifactory
import io.github.fiftieshousewife.appcore.Ecosystem
import io.github.fiftieshousewife.appcore.EcosystemSpec
import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.cmdQuote
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers.Companion.shQuote
import io.github.fiftieshousewife.nodeapp.tasks.GenerateNpmrc
import io.github.fiftieshousewife.nodeapp.tasks.NodeLayout
import io.github.fiftieshousewife.nodeapp.tasks.NpmProductionInstall
import io.github.fiftieshousewife.nodeapp.tasks.NpmTask
import io.github.fiftieshousewife.nodeapp.tasks.VerifyNodeModules
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
 * `io.github.fiftieshousewife.node-app` — a Node.js application packaged with the official Node
 * binary into a self-contained `.tar.gz`. Gradle drives npm with the same pinned Node distribution
 * that is bundled, so nothing needs to be installed on the build machine except a JDK for Gradle.
 *
 * Archive layout: `bin/<app>`, `runtime/bin/node` (`runtime/node.exe` on Windows),
 * `app/` (sources, `package.json`, production `node_modules`), `conf.defaults/`, `META-INF/`.
 * Dependencies live in `app/node_modules` rather than `lib/` because ES modules resolve packages
 * by walking up from the importing file and ignore `NODE_PATH`.
 */
abstract class NodeAppPlugin
    @Inject
    constructor(
        private val archives: ArchiveOperations,
        private val components: SoftwareComponentFactory,
    ) : Plugin<Project> {
        override fun apply(project: Project) {
            project.pluginManager.apply("base")
            val extension = project.extensions.create(ECOSYSTEM.extensionName, NodeAppExtension::class.java)
            val wiring = AppWiring(project, ECOSYSTEM, extension, archives, components)
            val sources = NodeSources.from(project.providers)
            wiring.applyConventions()
            applyConventions(project, extension)

            val extractHost = registerNodeDistribution(extension, wiring, sources, wiring.host)
            val node =
                PinnedNode(
                    home = project.layout.dir(extractHost.map { it.destinationDir }),
                    version = extension.nodeVersion,
                    extracted = extractHost,
                    npmrc = registerNpmrc(project, wiring, sources),
                )
            val npmBuild = registerNpmTasks(project, extension, node)
            val packageJsonFile = project.layout.projectDirectory.file("package.json")

            wiring.register { target ->
                // This machine's distribution drives npm; for its own target it is also the runtime.
                val distribution = if (target == wiring.host) extractHost else registerNodeDistribution(extension, wiring, sources, target)
                val productionInstall = registerProductionInstall(project, extension, wiring, node, target)
                val prodModules = productionInstall.flatMap { it.outputDir }.map { it.dir("node_modules") }
                val verifyModules = registerVerifyNodeModules(project, extension, wiring, target, prodModules)
                val runtime =
                    project.tasks.register("nodeRuntime${target.taskSuffix}", Sync::class.java) {
                        group = ECOSYSTEM.taskGroup
                        description = "Selects the node binary and licence from the ${target.id} distribution"
                        from(distribution) { include("bin/node", "node.exe", "LICENSE") }
                        into(wiring.workDir.map { it.dir("node-runtime/${target.id}") })
                    }
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
                }
            }
        }

        private fun applyConventions(
            project: Project,
            extension: NodeAppExtension,
        ) {
            val projectDir = project.layout.projectDirectory
            extension.nodeVersion.convention(NodeCatalog.nodeVersion(project.providers))
            // src/deploy/ holds conf.defaults and the Dockerfile override, so it is not application code.
            extension.appFiles.convention(
                extension.buildScript
                    .map { projectDir.dir("dist").asFileTree }
                    .orElse(projectDir.dir("src").asFileTree.matching { exclude("deploy/**") }),
            )
            extension.entryPoint.convention("main.js")
            extension.testScript.convention("test")
            extension.ignoreInstallScripts.convention(true)
            extension.nodeArgs.convention(emptyList())
            extension.portableInstallScripts.convention(emptyList())
        }

        /** Resolves and extracts the pinned Node.js distribution for [target]. */
        private fun registerNodeDistribution(
            extension: NodeAppExtension,
            wiring: AppWiring,
            sources: NodeSources,
            target: Target,
        ): TaskProvider<Sync> {
            val archive =
                wiring.runtimeArchive("node${target.taskSuffix}", extension.nodeVersion.map { v -> sources.artifact(v, target) })
            return wiring.extractRuntime(
                "extractNode${target.taskSuffix}",
                wiring.singleFile(archive),
                wiring.workDir.map { it.dir("node/${target.id}") },
            )
        }

        private fun registerVerifyNodeModules(
            project: Project,
            extension: NodeAppExtension,
            wiring: AppWiring,
            target: Target,
            prodModules: Provider<Directory>,
        ) = project.tasks.register("verifyNodeModules${target.taskSuffix}", VerifyNodeModules::class.java) {
            group = AppWiring.VERIFICATION_GROUP
            description = "Checks the ${target.id} production node_modules for host-specific install-script output"
            nodeModules.set(prodModules)
            crossBuild.set(target != wiring.host)
            ignoredScripts.set(extension.ignoreInstallScripts)
            portable.set(extension.portableInstallScripts)
            reportFile.set(wiring.workDir.map { it.file("node-modules-report/${target.id}.txt") })
        }

        private fun registerNpmrc(
            project: Project,
            wiring: AppWiring,
            sources: NodeSources,
        ) = project.tasks.register("npmrc", GenerateNpmrc::class.java) {
            group = ECOSYSTEM.taskGroup
            description = "Writes the npm user config for the configured npm registry"
            registry.set(sources.npmRegistry.toString())
            caFile.set(project.providers.gradleProperty(NodeSources.NPM_CA_FILE))
            if (sources.usesArtifactory) {
                credentials.set(project.providers.credentials(PasswordCredentials::class.java, Artifactory.CREDENTIALS_NAME))
            }
            outputFile.set(wiring.workDir.map { it.file("npm/npmrc") })
        }

        /** `npmInstall`, `npmBuild` and `npmTest` over the working tree; returns `npmBuild`. */
        private fun registerNpmTasks(
            project: Project,
            extension: NodeAppExtension,
            node: PinnedNode,
        ): TaskProvider<NpmTask> {
            val tasks = project.tasks
            val projectDir = project.layout.projectDirectory
            val packageJsonFile = projectDir.file("package.json")

            val npmInstall =
                tasks.register("npmInstall", NpmTask::class.java) {
                    group = ECOSYSTEM.taskGroup
                    description = "npm ci — installs all dependencies for building and testing"
                    use(node)
                    workingDirectory.set(projectDir)
                    npmArgs.set(listOf("ci"))
                    inputs.files(packageJsonFile, projectDir.file("package-lock.json")).withPropertyName("packageFiles")
                    outputs.dir(projectDir.dir("node_modules")).withPropertyName("nodeModules")
                }

            val npmBuild =
                tasks.register("npmBuild", NpmTask::class.java) {
                    group = ECOSYSTEM.taskGroup
                    description = "Runs the package.json script named by nodeApp.buildScript, if set"
                    use(node)
                    dependsOn(npmInstall)
                    workingDirectory.set(projectDir)
                    npmArgs.set(extension.buildScript.map { listOf("run", it) }.orElse(emptyList()))
                    val script = extension.buildScript
                    onlyIf("nodeApp.buildScript is set") { script.isPresent }
                }

            val npmTest =
                tasks.register("npmTest", NpmTask::class.java) {
                    group = AppWiring.VERIFICATION_GROUP
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
            extension: NodeAppExtension,
            wiring: AppWiring,
            node: PinnedNode,
            target: Target,
        ) = project.tasks.register("npmInstallProduction${target.taskSuffix}", NpmProductionInstall::class.java) {
            group = ECOSYSTEM.taskGroup
            description = "npm ci --omit=dev for ${target.id}, isolated from the working tree"
            dependsOn(node.extracted, node.npmrc)
            nodeHome.set(node.home)
            nodeVersion.set(node.version)
            npmrc.set(node.npmrc.flatMap { it.outputFile })
            packageJson.set(project.layout.projectDirectory.file("package.json"))
            packageLock.set(project.layout.projectDirectory.file("package-lock.json"))
            val host = wiring.host
            npmArgs.set(
                extension.ignoreInstallScripts.map { ignore ->
                    val args = mutableListOf<String>()
                    if (ignore) args += "--ignore-scripts"
                    if (target != host) {
                        // Install the target's OS- and CPU-specific optional dependencies (npm 10+).
                        args += "--os=${NodeSources.npmOs(target)}"
                        args += "--cpu=${NodeSources.npmCpu(target)}"
                        if (target.os == Os.LINUX) args += "--libc=glibc"
                    }
                    args
                },
            )
            outputDir.set(wiring.workDir.map { it.dir("npm-production/${target.id}") })
        }

        private fun ecosystemSpec(
            extension: NodeAppExtension,
            prodModules: Provider<Directory>,
            contents: Sync.() -> Unit,
        ) = EcosystemSpec(
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

        companion object {
            val ECOSYSTEM =
                Ecosystem(
                    id = "node",
                    extensionName = "nodeApp",
                    settingsPluginId = NodeAppSettingsPlugin.ID,
                )
        }
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
