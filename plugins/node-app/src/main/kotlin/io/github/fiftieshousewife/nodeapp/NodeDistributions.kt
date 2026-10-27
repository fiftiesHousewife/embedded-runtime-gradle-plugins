package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/** Resolves and extracts the pinned Node.js distribution for a target. */
internal class NodeDistributions(
    project: Project,
    private val extension: NodeAppExtension,
    private val wiring: AppWiring,
) {
    private val sources = NodeSources.from(project.providers)

    fun forTarget(target: Target): TaskProvider<Sync> {
        val archive = wiring.runtimeArchive(
            "node${target.taskSuffix}",
            extension.nodeVersion.map {
                sources.artifact(it, target)
            },
        )
        return wiring.extractRuntime(
            "extractNode${target.taskSuffix}",
            wiring.singleFile(archive),
            wiring.workDir.map { it.dir("node/${target.id}") },
        )
    }
}
