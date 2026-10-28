package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GenerateAppManifest
import io.github.fiftieshousewife.appcore.tasks.GenerateLaunchers
import io.github.fiftieshousewife.appcore.tasks.GenerateSbom
import io.github.fiftieshousewife.appcore.tasks.VerifyRuntimePolicy
import org.gradle.api.Project
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

/** The tasks built once per target: launchers, manifest and SBOM, assembled into a directory and archived. */
internal class TargetPipeline(private val project: Project, private val wiring: AppWiring) {
    private val providers = project.providers
    private val layout = project.layout
    private val tasks = project.tasks
    private val extension = wiring.extension
    private val workDir = wiring.workDir
    private val taskGroup = wiring.taskGroup

    private val appName: Provider<String> get() = extension.appName
    private val versionString: Provider<String> = wiring.version
    private val groupString: Provider<String> = providers.provider { project.group.toString() }

    /** `<app>-<version>`: the installed app directory's name. */
    private val topDirectory: Provider<String> get() = appName.zip(versionString) { n, v -> "$n-$v" }

    fun register(target: Target, spec: EcosystemSpec): TargetTasks {
        val builtIn = registerSbom(target, spec)
        val sbom = extension.sbom.orElse(builtIn.flatMap { it.outputFile })
        val parts = Parts(registerLaunchers(target, spec), registerManifest(target, spec), sbom)
        val name = distributionName(target)
        registerDistribution(name, target, spec, parts)
        val install = tasks.named(target.installTaskName, Sync::class.java) {
            group = taskGroup
            description = "Installs the self-contained ${target.id} app into build/app/${target.id}"
            destinationDir = layout.buildDirectory.dir("app/${target.id}").get().asFile.resolve(topDirectory.get())
        }
        val verified = registerPolicyCheck(target, install)
        val archive = archiveTask(name, target).apply { configure { dependsOn(verified) } }
        return TargetTasks(target, install, archive, parts.sbom)
    }

    /** The generated pieces every target's directory holds besides the ecosystem's own content. */
    private class Parts(
        /** A task or file tree: whatever `bin/` is copied from. */
        val launchers: Any,
        val manifest: TaskProvider<GenerateAppManifest>,
        val sbom: Provider<RegularFile>,
    )

    /**
     * One Gradle distribution per target, named after it (`linuxX64`, `macAarch64`), so each gets the
     * distribution plugin's `install…Dist`, `…DistTar` and `…DistZip`. This machine's contents also
     * go into the `main` distribution, so plain `installDist` installs this machine's app with its runtime.
     */
    private fun registerDistribution(name: String, target: Target, spec: EcosystemSpec, parts: Parts) {
        val app = appName
        val contents = contents(spec, parts)
        val distributions = project.extensions.getByType(DistributionContainer::class.java)
        distributions.register(name) {
            distributionBaseName.set(app)
            contents {
                duplicatesStrategy = DuplicatesStrategy.FAIL
                with(contents)
            }
        }
        if (target == wiring.host) {
            distributions.named("main") {
                // Java's main distribution already holds Gradle's bin/ and lib/ for the same files.
                contents {
                    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                    with(contents)
                }
            }
        }
    }

    /** What one target's app holds: launchers, metadata, default configuration and the ecosystem's own. */
    private fun contents(spec: EcosystemSpec, parts: Parts) = project.copySpec {
        into("bin") {
            from(parts.launchers)
            filePermissions { unix("rwxr-xr-x") }
        }
        into("META-INF") {
            from(parts.manifest)
            from(parts.sbom) { rename { "sbom.cdx.json" } }
        }
        into("conf.defaults") { from(extension.configDefaults.map { listOf(it) }.orElse(emptyList())) }
        spec.contents(this)
        AppCopySpecs.executableBits(this)
    }

    /**
     * The archive native to [target] (zip for Windows, else tar.gz), named `<app>-<version>-<target>.<ext>`
     * in `build/distributions`, the classifier the Maven publication uses; the other format's task is disabled.
     */
    private fun archiveTask(name: String, target: Target): TaskProvider<out AbstractArchiveTask> {
        val format = ArchiveFormat.nativeTo(target)
        val tar = tasks.named("${name}DistTar", Tar::class.java) {
            enabled = format != ArchiveFormat.ZIP
            compression = if (format == ArchiveFormat.TAR_GZ) Compression.GZIP else Compression.NONE
        }
        val zip = tasks.named("${name}DistZip", Zip::class.java) {
            enabled = format == ArchiveFormat.ZIP
        }
        val chosen = if (format == ArchiveFormat.ZIP) zip else tar
        chosen.configure {
            group = taskGroup
            description = "Archives the app for ${target.id} as <app>-<version>-${target.id}.$format"
            // Classifier and extension, not a file name: the publication reads both from the task,
            // and the distribution names the archive's top-level directory <app>-<version>-<target>.
            archiveBaseName.set(appName)
            archiveVersion.set(versionString)
            archiveClassifier.set(target.id)
            archiveExtension.set(format.extension)
            destinationDirectory.set(layout.buildDirectory.dir("distributions"))
            // Gradle 9 normalises modes in archives; keep the runtime's own exec bits (jspawnhelper).
            useFileSystemPermissions()
        }
        return chosen
    }

    private fun distributionName(target: Target) = target.taskSuffix.replaceFirstChar(Char::lowercase)

    /** No archive breaking the organisation's runtime policy is built. */
    private fun registerPolicyCheck(target: Target, assemble: TaskProvider<Sync>) =
        tasks.register("appVerifyPolicy${target.taskSuffix}", VerifyRuntimePolicy::class.java) {
            group = AppWiring.VERIFICATION_GROUP
            description = "Checks the ${target.id} app against the organisation's runtime policy, if one is set"
            appDirectory.set(layout.dir(assemble.map { it.destinationDir }))
            policy.from(wiring.runtimePolicy)
            resultFile.set(workDir.map { it.file("policy/${target.id}.txt") })
        }

    /** This target's `bin/`: its own script from `startScripts`, or one generated for it. */
    private fun registerLaunchers(target: Target, spec: EcosystemSpec): Any = when (val launchers = spec.launchers) {
        is Launchers.StartScripts ->
            project.files(launchers.task).asFileTree.matching {
                if (target.isWindows) include("*.bat") else exclude("*.bat")
            }

        is Launchers.Generated ->
            tasks.register("appLaunchers${target.taskSuffix}", GenerateLaunchers::class.java) {
                group = taskGroup
                description = "Generates the bin/ launcher for ${target.id}"
                appName.set(this@TargetPipeline.appName)
                windows.set(target.isWindows)
                posixExec.set(launchers.posixExec)
                windowsExec.set(launchers.windowsExec)
                lockedOptions.set(wiring.facts.runtimeOptions.map { it == RuntimeOptions.LOCKED })
                outputDir.set(workDir.map { it.dir("launchers/${target.id}") })
            }
    }

    private fun registerManifest(target: Target, spec: EcosystemSpec) =
        tasks.register("appManifest${target.taskSuffix}", GenerateAppManifest::class.java) {
            group = taskGroup
            description = "Generates META-INF/app.json for ${target.id}"
            appGroup.set(groupString)
            appName.set(this@TargetPipeline.appName)
            version.set(versionString)
            this.target.set(target.id)
            ecosystem.set(wiring.ecosystem.id)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            gitCommit.set(wiring.gitCommit)
            outputFile.set(workDir.map { it.file("meta/${target.id}/app.json") })
        }

    private fun registerSbom(target: Target, spec: EcosystemSpec) =
        tasks.register("appSbom${target.taskSuffix}", GenerateSbom::class.java) {
            val replaced = extension.sbom
            onlyIf("the project supplies no SBOM of its own (sbom)") { !replaced.isPresent }
            group = taskGroup
            description = "Generates a CycloneDX SBOM for ${target.id}, including the runtime"
            appGroup.set(groupString)
            appName.set(this@TargetPipeline.appName)
            version.set(versionString)
            this.target.set(target.id)
            runtimeName.set(spec.runtimeName)
            runtimeVersion.set(spec.runtimeVersion)
            mavenComponents.convention(emptyList())
            spec.sbom(this)
            outputFile.set(workDir.map { it.file("meta/${target.id}/sbom.cdx.json") })
        }
}
