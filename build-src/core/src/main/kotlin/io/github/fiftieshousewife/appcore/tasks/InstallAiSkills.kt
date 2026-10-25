package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * `aiSkills`: writes the plugins' AI assistant skills into the project's `.agents/skills/` (read by
 * GitHub Copilot in its IDE agent modes, app, CLI and cloud agent, and by Devin) and their Copilot
 * prompt files into `.github/prompts/`. Only the `embedded-runtime-*` files are written, so a
 * project's own skills are left alone; running it again updates them to this plugin version.
 */
@DisableCachingByDefault(because = "Writes into the project's own source tree")
abstract class InstallAiSkills : DefaultTask() {
    @get:Internal abstract val projectDirectory: DirectoryProperty

    @TaskAction
    fun install() {
        val project = projectDirectory.get().asFile
        val written = resource("index.txt").lines().filter(String::isNotBlank).map { entry ->
            val target = if (entry.startsWith("skills/")) {
                project.resolve(".agents/skills/" + entry.removePrefix("skills/"))
            } else {
                project.resolve(".github/prompts/" + entry.removePrefix("prompts/"))
            }
            target.parentFile.mkdirs()
            target.writeText(resource(entry))
            target.relativeTo(project).invariantSeparatorsPath
        }
        written.forEach { logger.lifecycle("Wrote $it") }
    }

    private fun resource(path: String): String =
        InstallAiSkills::class.java.getResource("/io/github/fiftieshousewife/appcore/ai/$path")?.readText()
            ?: error("$path missing from the app-core jar")
}
