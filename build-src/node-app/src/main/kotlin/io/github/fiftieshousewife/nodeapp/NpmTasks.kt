package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.nodeapp.tasks.GenerateNpmrc
import io.github.fiftieshousewife.nodeapp.tasks.NodeLayout
import io.github.fiftieshousewife.nodeapp.tasks.NpmTask
import org.gradle.api.Project
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/** This machine's Node.js, which drives npm, and the npm config it reads. */
internal class PinnedNode(
    val home: Provider<Directory>,
    val version: Provider<String>,
    val extracted: TaskProvider<Sync>,
    val npmrc: TaskProvider<GenerateNpmrc>,
)

/** `npmInstall`, `npmBuild` and `npmTest` over the working tree, with this machine's pinned Node. */
internal class NpmTasks(
    private val project: Project,
    private val extension: NodeAppExtension,
    private val node: PinnedNode,
) {
    private val projectDir = project.layout.projectDirectory
    private val packageJson = projectDir.file("package.json")
    private val group = NodeAppPlugin.ECOSYSTEM.taskGroup

    /** Registers all three; returns `npmBuild`, which packaging depends on. */
    fun register(): TaskProvider<NpmTask> {
        val install = install()
        val build = build(install)
        val test = test(install, build)
        project.tasks.named("check") { dependsOn(test) }
        return build
    }

    private fun install() = project.tasks.register("npmInstall", NpmTask::class.java) {
        group = this@NpmTasks.group
        description = "npm ci — installs all dependencies for building and testing"
        use(node)
        npmArgs.set(listOf("ci"))
        inputs.files(packageJson, projectDir.file("package-lock.json")).withPropertyName("packageFiles")
        outputs.dir(projectDir.dir("node_modules")).withPropertyName("nodeModules")
    }

    private fun build(install: TaskProvider<NpmTask>) = project.tasks.register("npmBuild", NpmTask::class.java) {
        group = this@NpmTasks.group
        description = "Runs the package.json script named by nodeApp.buildScript, if set"
        use(node)
        dependsOn(install)
        npmArgs.set(extension.buildScript.map { listOf("run", it) }.orElse(emptyList()))
        val script = extension.buildScript
        onlyIf("nodeApp.buildScript is set") { script.isPresent }
    }

    private fun test(install: TaskProvider<NpmTask>, build: TaskProvider<NpmTask>) =
        project.tasks.register("npmTest", NpmTask::class.java) {
            group = AppWiring.VERIFICATION_GROUP
            description = "Runs the package.json test script, if present"
            use(node)
            dependsOn(install, build)
            npmArgs.set(extension.testScript.map { listOf("run", it) })
            val manifest = packageJson.asFile
            val script = extension.testScript
            onlyIf("package.json defines the test script") { NodeLayout.hasScript(manifest, script.get()) }
        }

    private fun NpmTask.use(node: PinnedNode) {
        workingDirectory.set(projectDir)
        nodeHome.set(node.home)
        nodeVersion.set(node.version)
        npmrc.set(node.npmrc.flatMap { it.outputFile })
        environment.convention(emptyMap())
        dependsOn(node.extracted, node.npmrc)
    }

    companion object {
        /** The npm user config: the configured registry, a corporate CA, and binary repository credentials. */
        fun npmrc(project: Project, wiring: AppWiring, sources: NodeSources) =
            project.tasks.register("npmrc", GenerateNpmrc::class.java) {
                group = NodeAppPlugin.ECOSYSTEM.taskGroup
                description = "Writes the npm user config for the configured npm registry"
                registry.set(sources.npmRegistry.toString())
                caFile.set(project.providers.gradleProperty(NodeSources.NPM_CA_FILE))
                if (sources.usesBinaryRepo) {
                    credentials.set(
                        project.providers.credentials(
                            PasswordCredentials::class.java,
                            BinaryRepository.CREDENTIALS_NAME,
                        ),
                    )
                }
                outputFile.set(wiring.workDir.map { it.file("npm/npmrc") })
            }
    }
}
