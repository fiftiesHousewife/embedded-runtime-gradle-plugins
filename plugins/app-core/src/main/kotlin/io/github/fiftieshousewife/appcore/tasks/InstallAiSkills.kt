package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.ClaudeSettings
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Runs with every build, as the Dockerfile is kept current, so a project has the skills of the plugin
 * release it builds with: `.agents/skills/` (Copilot and Devin), the post-edit check in `.github/hooks/`,
 * Devin's hook file only if the project has none, and the Claude Code plugin in `.claude/settings.json`.
 * Every file is named `embedded-runtime-*`, so a project's own are left alone, and a file is written
 * only when it differs, so a checkout stays clean.
 */
@DisableCachingByDefault(because = "Writes into the project's own source tree")
abstract class InstallAiSkills : DefaultTask() {
    @get:Internal abstract val projectDirectory: DirectoryProperty

    @TaskAction
    fun install() {
        val project = projectDirectory.get().asFile
        resource("index.txt").lines().filter(String::isNotBlank).forEach { entry ->
            val target = destination(project, entry) ?: return@forEach
            if (entry.startsWith("devin/") && target.exists()) {
                logger.info("Kept ${target.name}; add the embedded-runtime hook from the plugin's ai/devin by hand")
            } else {
                write(target, resource(entry), project)
            }
        }
        val claude = project.resolve(".claude/settings.json")
        ClaudeSettings.withPlugin(claude.takeIf(File::isFile)?.readText())?.let { write(claude, it, project) }
    }

    private fun destination(project: File, entry: String): File? {
        val (kind, rest) = entry.substringBefore('/') to entry.substringAfter('/')
        return when (kind) {
            "skills" -> project.resolve(".agents/skills/$rest")
            "hooks", "copilot-hooks" -> project.resolve(".github/hooks/$rest")
            "devin" -> project.resolve(".devin/$rest")
            else -> null
        }
    }

    private fun write(target: File, text: String, project: File) {
        if (target.isFile && target.readText() == text) return
        target.parentFile.mkdirs()
        target.writeText(text)
        if (target.extension == "sh") target.setExecutable(true, false)
        logger.lifecycle("Wrote ${target.relativeTo(project).invariantSeparatorsPath}")
    }

    private fun resource(path: String): String =
        InstallAiSkills::class.java.getResource("/io/github/fiftieshousewife/appcore/ai/$path")?.readText()
            ?: error("$path missing from the app-core jar")
}
