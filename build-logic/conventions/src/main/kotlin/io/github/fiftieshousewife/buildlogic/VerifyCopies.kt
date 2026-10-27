package io.github.fiftieshousewife.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Fails when a copy differs from its sources: each key a directory, each value the directories it copies. */
abstract class VerifyCopies : DefaultTask() {
    @get:Internal
    abstract val root: DirectoryProperty

    @get:Input
    abstract val copies: MapProperty<String, List<String>>

    @TaskAction
    fun verify() {
        val base = root.get().asFile
        val stale = copies.get().filter { (copy, sources) ->
            sources.map { tree(base.resolve(it)) }.reduce { a, b -> a + b } != tree(base.resolve(copy))
        }.keys
        if (stale.isNotEmpty()) throw GradleException("Out of date: ${stale.joinToString()}. Run ./gradlew syncAiSkills")
    }

    private fun tree(dir: File): Map<String, List<Byte>> =
        dir.walkTopDown().filter(File::isFile).associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }
}
