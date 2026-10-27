package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.RuntimePolicyCheck
import io.github.fiftieshousewife.appcore.RuntimePolicySource
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.archiveTree
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
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
    private val workers: WorkerExecutor,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFiles: ConfigurableFileCollection

    @get:Input abstract val healthTimeout: Property<Duration>

    /** Appended to a failure, e.g. how to find a missing JDK module. */
    @get:Input @get:Optional
    abstract val failureHint: Property<String>

    /**
     * The organisation's runtime policy: an archive that breaks it fails before it is started,
     * however it was built. Empty means no policy is required.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val requiredPolicy: ConfigurableFileCollection

    /**
     * Who must have signed every archive. When set, each archive needs a Sigstore bundle in
     * [signatures] (`<archive>.sigstore.json`) that verifies against it, or nothing is started.
     */
    @get:Input @get:Optional
    abstract val signer: Property<SignerIdentity>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val signatures: ConfigurableFileCollection

    /** Sigstore's `trusted_root.json`, for a machine that can't fetch it; see [SigstoreVerification]. */
    @get:InputFile @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val trustedRoot: RegularFileProperty

    /** sigstore-java and its dependencies, loaded only by the verifying worker. */
    @get:Classpath abstract val verifierClasspath: ConfigurableFileCollection

    @get:OutputFile abstract val resultFile: RegularFileProperty

    init {
        onlyIf("there is an archive to test") { !archiveFiles.isEmpty }
    }

    @TaskAction
    fun smokeTest() {
        signer.orNull?.let(::verifySignatures)
        val results = archiveFiles.files.sortedBy { it.name }.mapIndexed(::test)
        resultFile.get().asFile.writeText(results.joinToString("\n") + "\n")
    }

    private fun test(index: Int, archiveFile: File): String {
        val host = Target.host()
        val home = extractTopDirectory(archiveFile, temporaryDir.resolve("extract-$index"))
        requirePolicy(home)
        val manifest = AppManifest.read(home)
        val work = temporaryDir.resolve("run-$index").apply { mkdirs() }
        val launcher = launcher(home, manifest, host)
        val result = SmokeRun(launcher, host.isWindows, work, healthTimeout.get()).run(manifest.version)
        if (result is SmokeRun.Result.Failed) {
            val hint = failureHint.orNull?.let { "\n\n$it" }.orEmpty()
            throw GradleException("${manifest.name} ${manifest.version} ${result.reason}:\n${result.tail}$hint")
        }
        logger.lifecycle("Smoke test passed: ${archiveFile.name} started and reported healthy")
        return "healthy ${manifest.name} ${manifest.version}"
    }

    private fun verifySignatures(signer: SignerIdentity) {
        val bundles = signatures.files.associateBy { it.name }
        val queue = workers.classLoaderIsolation { classpath.from(verifierClasspath) }
        archiveFiles.files.sortedBy { it.name }.forEach { archive ->
            val bundle = bundles[archive.name + BUNDLE_SUFFIX]
                ?: throw GradleException(
                    "${archive.name} has no Sigstore bundle: ${archive.name}$BUNDLE_SUFFIX was not found beside " +
                        "it in the repository, so it can't be shown to come from ${signer.identity}",
                )
            queue.submit(VerifySigstoreBundle::class.java) {
                this.archive.set(archive)
                this.bundle.set(bundle)
                identity.set(signer.identity)
                issuer.set(signer.issuer)
                this.trustedRoot.set(this@AppSmokeTest.trustedRoot)
            }
        }
        queue.await()
        logger.lifecycle("Signatures verified: every archive was signed by ${signer.identity}")
    }

    private fun launcher(home: File, manifest: AppManifest, host: Target): File {
        if (manifest.target != host.id) {
            throw GradleException(
                "${manifest.name} ${manifest.version} is for ${manifest.target}; this machine is $host, " +
                    "so it cannot start here",
            )
        }
        val launcher = home.resolve(if (host.isWindows) "bin/${manifest.name}.bat" else "bin/${manifest.name}")
        if (!launcher.isFile) throw GradleException("Launcher $launcher missing")
        return launcher
    }

    private fun requirePolicy(home: File) {
        val policy = RuntimePolicySource.load(requiredPolicy.files, fs, archives, temporaryDir.resolve("policy"))
            ?: return
        val violations = RuntimePolicyCheck.violations(policy, home)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "${home.name} breaks runtime policy ${policy.label}:\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("${home.name} complies with runtime policy ${policy.label}")
    }

    private fun extractTopDirectory(archiveFile: File, into: File): File {
        fs.sync {
            from(archives.archiveTree(archiveFile))
            into(into)
        }
        return into.listFiles()?.singleOrNull { it.isDirectory }
            ?: throw GradleException("Archive $archiveFile must contain exactly one top-level directory")
    }

    companion object {
        /** What `dev.sigstore.sign` and Maven Central name a file's bundle: `<file>.sigstore.json`. */
        const val BUNDLE_SUFFIX = ".sigstore.json"
    }
}
