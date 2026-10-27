package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import org.gradle.api.Task
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.CopySpec
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask

/** The names that tell the Java and Node plugins apart wherever they share code. */
data class Ecosystem(
    /** `java` or `node`, recorded in `META-INF/app.json`. */
    val id: String,
    /** `javaApp` or `nodeApp`: the DSL block, and the prefix of the plugin's Gradle properties. */
    val extensionName: String,
    val settingsPluginId: String,
) {
    val taskGroup: String get() = "$id app"

    fun property(name: String) = "$extensionName.$name"
}

data class AppServices(val archives: ArchiveOperations, val components: SoftwareComponentFactory)

/** Where an ecosystem's launchers come from: rendered from start lines (Node), or Gradle's `startScripts` (Java). */
sealed interface Launchers {
    data class Generated(val posixExec: Provider<String>, val windowsExec: Provider<String>) : Launchers

    data class StartScripts(val task: TaskProvider<out Task>) : Launchers
}

data class EcosystemSpec(
    val runtimeName: String,
    val runtimeVersion: Provider<String>,
    val launchers: Launchers,
    /** The ecosystem's own content: `runtime/`, plus `lib/` or `app/`. */
    val contents: CopySpec.() -> Unit,
    val sbom: GenerateSbom.() -> Unit,
)

/** The targets whose archives are never started here: all but this machine's, and that one too without a smoke test. */
internal fun untestedTargets(targets: Collection<Target>, host: Target, smokeTestEnabled: Boolean): List<Target> =
    targets.filter { it != host || !smokeTestEnabled }

class TargetTasks(
    val target: Target,
    val assemble: TaskProvider<Sync>,
    val archive: TaskProvider<out AbstractArchiveTask>,
    /** The built-in SBOM, or the project's own ([AppExtension.sbom]). */
    val sbom: Provider<RegularFile>,
)
