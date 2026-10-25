package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppServices
import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Ecosystem
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
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
        project.pluginManager.apply("distribution")
        // The default distribution would be empty: every target has a distribution of its own.
        listOf("distTar", "distZip", "installDist").forEach { name -> project.tasks.named(name) { enabled = false } }
        val extension = project.extensions.create(ECOSYSTEM.extensionName, NodeAppExtension::class.java)
        val wiring = AppWiring(project, ECOSYSTEM, extension, AppServices(archives, components))
        wiring.applyConventions()
        NodeConventions.apply(project, extension)

        val sources = NodeSources.from(project.providers)
        val extracted = NodeDistributions(project, extension, wiring).forTarget(wiring.host)
        val node =
            PinnedNode(
                home = project.layout.dir(extracted.map { it.destinationDir }),
                version = extension.nodeVersion,
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
