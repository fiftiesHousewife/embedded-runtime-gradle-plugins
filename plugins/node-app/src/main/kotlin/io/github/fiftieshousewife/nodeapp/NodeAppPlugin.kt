package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppServices
import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Ecosystem
import io.github.fiftieshousewife.appcore.EmbeddedRuntime
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
import javax.inject.Inject

/**
 * npm runs on the same pinned Node that is bundled, so the build machine needs only a JDK. Dependencies live in
 * `app/node_modules`, not `lib/`, because ES modules resolve packages upwards and ignore `NODE_PATH`.
 */
abstract class NodeAppPlugin
@Inject
constructor(
    private val archives: ArchiveOperations,
    private val components: SoftwareComponentFactory,
) : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("base")
        project.pluginManager.apply("distribution")
        // Every target has a distribution of its own; main holds this machine's, for installDist.
        listOf("distTar", "distZip").forEach { name -> project.tasks.named(name) { enabled = false } }
        val extension = project.extensions.create(ECOSYSTEM.extensionName, NodeAppExtension::class.java)
        val wiring = AppWiring(project, ECOSYSTEM, extension, AppServices(archives, components))
        wiring.applyConventions()
        val version = NodeCatalog.nodeVersion(project.providers)
        wiring.registerRuntimeSupportCheck(
            version.map { EmbeddedRuntime("nodejs", it.substringBefore('.'), it) },
        )
        NodeConventions.apply(project, extension)

        val sources = NodeSources.from(project.providers)
        val extracted = NodeDistributions(project, version, wiring).forTarget(wiring.host)
        val node =
            PinnedNode(
                home = project.layout.dir(extracted.map { it.destinationDir }),
                version = version,
                extracted = extracted,
                npmrc = NpmTasks.npmrc(project, wiring, sources),
            )
        val npmBuild = NpmTasks(project, extension, node).register()
        val targets = NodeTargetTasks(project, extension, wiring, node)
        wiring.register { target -> targets.spec(target, npmBuild) }
    }

    companion object {
        val ECOSYSTEM =
            Ecosystem(
                id = "node",
                extensionName = "nodeApp",
                settingsPluginId = NodeAppSettingsPlugin.ID,
            )
    }
}
