package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.VerifyRuntimeFingerprint
import io.github.fiftieshousewife.appcore.tasks.WriteRuntimeFingerprint
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily

/**
 * `appRuntimeFingerprint<Target>`, published beside each runtime archive, and `appVerifyRuntimeFingerprint`, which
 * compares this build's runtimes with an earlier release's (`<javaApp|nodeApp>.previousRelease`) before publishing.
 */
internal class RuntimeFingerprints(private val project: Project, private val wiring: AppWiring) {
    fun register(target: Target, spec: EcosystemSpec): TaskProvider<WriteRuntimeFingerprint> =
        project.tasks.register("appRuntimeFingerprint${target.taskSuffix}", WriteRuntimeFingerprint::class.java) {
            group = wiring.taskGroup
            description = "Lists what the ${target.id} runtime archive is built from, to publish beside it"
            entries.put("target", target.id)
            entries.put("runtime", spec.runtimeVersion.map { "${spec.runtimeName} $it" })
            entries.put("plugin", PluginVersion.current)
            spec.fingerprint(this)
            val name = wiring.extension.appName.zip(wiring.version) { app, version ->
                "$app${RuntimeArchive.SUFFIX}-$version-${target.id}" +
                    "${RuntimeFingerprint.CLASSIFIER_SUFFIX}.${RuntimeFingerprint.EXTENSION}"
            }
            outputFile.set(project.layout.buildDirectory.dir("distributions").zip(name) { dir, file -> dir.file(file) })
        }

    /** Runs before publishing when an earlier release is named; on its own, it shows what a release would store. */
    fun guard(built: List<TargetTasks>): TaskProvider<VerifyRuntimeFingerprint> {
        val previousRelease = wiring.facts.previousRelease
        val property = wiring.ecosystem.property("previousRelease")
        return project.tasks.register("appVerifyRuntimeFingerprint", VerifyRuntimeFingerprint::class.java) {
            group = AppWiring.VERIFICATION_GROUP
            description = "Fails when a runtime's inputs equal the previous release's ($property) but its bytes don't"
            onlyIf("$property names the release to compare with") { previousRelease.isPresent }
            targets.set(built.map { it.target.id })
            this.previousRelease.set(previousRelease.orElse(""))
            val views = built.map { previousFiles(it.target, previousRelease.orElse("")) }
            previous.from(views.map { it.files })
            unreadable.set(project.provider { views.flatMap { unreadable(it.artifacts.failures) } })
            current.from(built.map { it.runtimeArchive }, built.map { it.runtimeFingerprint })
            reportFile.set(wiring.workDir.map { it.file("runtime-fingerprint.txt") })
        }
    }

    /** The earlier release's archive and fingerprint for [target], if the repository has them. */
    private fun previousFiles(target: Target, release: Provider<String>): ArtifactView {
        val configurations = project.configurations
        val declared = configurations.dependencyScope("appPreviousRuntime${target.taskSuffix}") {
            description = "The previous release's ${target.id} runtime archive and fingerprint"
            dependencies.addAllLater(
                release.zip(wiring.extension.appName) { version, app ->
                    val module = "${project.group}:$app${RuntimeArchive.SUFFIX}:$version"
                    if (version.isEmpty()) emptyList() else listOf(dependency(target, module))
                },
            )
        }
        val resolvable = configurations.resolvable("appPreviousRuntimeFiles${target.taskSuffix}") {
            extendsFrom(declared.get())
            isTransitive = false
            attributes {
                attribute(
                    OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                    project.objects.named(OperatingSystemFamily::class.java, AppPublishing.osFamilyOf(target)),
                )
                attribute(
                    MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                    project.objects.named(MachineArchitecture::class.java, AppPublishing.architectureOf(target)),
                )
            }
        }
        // Lenient, so a target the earlier release did not have is reported as new; [unreadable] keeps other failures.
        return resolvable.get().incoming.artifactView { isLenient = true }
    }

    /** The runtime module at [notation], asking for the target's archive and fingerprint by classifier. */
    private fun dependency(target: Target, notation: String): ExternalModuleDependency =
        (project.dependencies.create(notation) as ExternalModuleDependency).apply {
            artifact {
                name = notation.split(':')[1]
                classifier = target.id
                extension = ArchiveFormat.nativeTo(target).extension
            }
            artifact {
                name = notation.split(':')[1]
                classifier = target.id + RuntimeFingerprint.CLASSIFIER_SUFFIX
                extension = RuntimeFingerprint.EXTENSION
            }
        }

    companion object {
        /** Failures other than the release or its file not being there; matched by name, the types being internal. */
        internal fun unreadable(failures: Collection<Throwable>): List<String> = failures
            .filterNot { failure ->
                generateSequence(failure) { it.cause }.any { it.javaClass.simpleName.endsWith(NOT_FOUND) }
            }
            .map { generateSequence(it) { cause -> cause.cause }.last().message ?: it.toString() }

        private const val NOT_FOUND = "NotFoundException"
    }
}
