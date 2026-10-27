package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Skills go to `.agents/skills/` (Copilot and Devin), the post-edit check to `.github/hooks/`, and Devin's hook
 * file only if the project has none. Every file is named `embedded-runtime-*`, so a project's own are left alone.
 */
@DisableCachingByDefault(because = "Writes into the project's own source tree")
abstract class InstallAiSkills : DefaultTask() {
    @get:Internal abstract val projectDirectory: DirectoryProperty

    @TaskAction
    fun install() {
        val project = projectDirectory.get().asFile
        resource("index.txt").lines().filter(String::isNotBlank).forEach { entry ->
            val target = destination(project, entry)
            when {
                target == null -> Unit

                entry.startsWith("devin/") && target.exists() ->
                    logger.lifecycle(
                        "Kept ${target.relativeTo(
                            project,
                        )}; add the embedded-runtime hook from the plugin's ai/devin by hand",
                    )

                else -> write(target, entry, project)
            }
        }
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

    private fun write(target: File, entry: String, project: File) {
        target.parentFile.mkdirs()
        target.writeText(resource(entry))
        if (target.extension == "sh") target.setExecutable(true, false)
        logger.lifecycle("Wrote ${target.relativeTo(project).invariantSeparatorsPath}")
    }

    private fun resource(path: String): String =
        InstallAiSkills::class.java.getResource("/io/github/fiftieshousewife/appcore/ai/$path")?.readText()
            ?: error("$path missing from the app-core jar")
}
