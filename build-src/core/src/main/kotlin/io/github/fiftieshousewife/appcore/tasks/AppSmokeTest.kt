package io.github.fiftieshousewife.appcore.tasks

import groovy.json.JsonSlurper
import io.github.fiftieshousewife.appcore.Target
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.time.Duration
import javax.inject.Inject

/**
 * Proves an archive itself works, not the build directories it came from: extracts each of
 * [archives] afresh and starts it once with [SmokeRun]. Each archive describes itself in
 * `META-INF/app.json` (name, version, target), so the same task tests the archive a build has just
 * made and one a consuming build has resolved from a repository.
 *
 * Only an archive for this machine can be started; archives for other targets are never started
 * here, and publishing them is guarded (see [VerifyPublishable]).
 */
@DisableCachingByDefault(because = "Executes the application; must run on the machine that publishes")
abstract class AppSmokeTest
    @Inject
    constructor(
        private val fs: FileSystemOperations,
        private val archives: ArchiveOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val archiveFiles: ConfigurableFileCollection

        @get:Input abstract val healthTimeout: Property<Duration>

        /** Appended to a failure, e.g. how to find a missing JDK module. */
        @get:Input @get:Optional
        abstract val failureHint: Property<String>

        @get:OutputFile abstract val resultFile: RegularFileProperty

        init {
            onlyIf("there is an archive to test") { !archiveFiles.isEmpty }
        }

        @TaskAction
        fun smokeTest() {
            val results = archiveFiles.files.sortedBy { it.name }.mapIndexed(::test)
            resultFile.get().asFile.writeText(results.joinToString("\n") + "\n")
        }

        private fun test(
            index: Int,
            archiveFile: File,
        ): String {
            val host = Target.host()
            val home = extract(archiveFile, temporaryDir.resolve("extract-$index"))
            val manifest = AppManifest.read(home)
            val work = temporaryDir.resolve("run-$index").apply { mkdirs() }
            val result = SmokeRun(launcher(home, manifest, host), host.isWindows, work, healthTimeout.get()).run(manifest.version)
            if (result is SmokeRun.Result.Failed) {
                val hint = failureHint.orNull?.let { "\n\n$it" }.orEmpty()
                throw GradleException("${manifest.name} ${manifest.version} ${result.reason}:\n${result.tail}$hint")
            }
            logger.lifecycle("Smoke test passed: ${archiveFile.name} started and reported healthy")
            return "healthy ${manifest.name} ${manifest.version}"
        }

        private fun launcher(
            home: File,
            manifest: AppManifest,
            host: Target,
        ): File {
            if (manifest.target != host.id) {
                throw GradleException(
                    "${manifest.name} ${manifest.version} is for ${manifest.target}; this machine is $host, so it cannot start here",
                )
            }
            val launcher = home.resolve(if (host.isWindows) "bin/${manifest.name}.bat" else "bin/${manifest.name}")
            if (!launcher.isFile) throw GradleException("Launcher $launcher missing")
            return launcher
        }

        /** Extracts [archiveFile] afresh and returns its single top-level directory. */
        private fun extract(
            archiveFile: File,
            into: File,
        ): File {
            fs.sync {
                from(archives.tarTree(archiveFile))
                into(into)
            }
            return into.listFiles()?.singleOrNull { it.isDirectory }
                ?: throw GradleException("Archive $archiveFile must contain exactly one top-level directory")
        }
    }

/** The fields of `META-INF/app.json` that identify an extracted app. */
internal data class AppManifest(
    val name: String,
    val version: String,
    val target: String,
) {
    companion object {
        fun read(home: File): AppManifest {
            val file = home.resolve("META-INF/app.json")
            if (!file.isFile) throw GradleException("$file missing: not an archive built by java-app or node-app")
            val json = JsonSlurper().parse(file) as Map<*, *>

            fun field(name: String) = json[name] as? String ?: throw GradleException("$file has no \"$name\"")
            return AppManifest(field("name"), field("version"), field("target"))
        }
    }
}

/** Fails publication of archives that were not smoke-tested here, unless explicitly allowed. */
@DisableCachingByDefault(because = "Verification only")
abstract class VerifyPublishable : DefaultTask() {
    /** Targets whose archives were never started on this machine. */
    @get:Input abstract val untestedTargets: ListProperty<String>

    @get:Input abstract val allowUntested: Property<Boolean>

    /** The Gradle property that overrides this guard, named in the failure message. */
    @get:Input abstract val allowUntestedProperty: Property<String>

    @TaskAction
    fun verify() {
        val untested = untestedTargets.get()
        if (untested.isEmpty()) return
        val what = "the ${untested.joinToString()} archive" + if (untested.size > 1) "s were" else " was"
        if (allowUntested.get()) {
            logger.warn("Publishing untested archives: $what never started here (${allowUntestedProperty.get()}=true)")
        } else {
            throw GradleException(
                "Refusing to publish: $what never started, because only this machine's archive can be smoke-tested " +
                    "here. Publish each from a build on that target (CI), or set -P${allowUntestedProperty.get()}=true " +
                    "(or allowUntestedPublish = true) to publish them untested.",
            )
        }
    }
}
