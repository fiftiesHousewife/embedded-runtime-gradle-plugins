package io.github.fiftieshousewife.javaapp.tasks

import io.github.fiftieshousewife.appcore.AppCopySpecs
import io.github.fiftieshousewife.appcore.RuntimePolicy
import io.github.fiftieshousewife.appcore.RuntimePolicySource
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Properties
import javax.inject.Inject

/**
 * Applies an organisation's changes to a linked runtime, in this order: refuses forbidden modules,
 * adds (or replaces) trusted CA certificates, applies security properties over
 * `conf/security/java.security`, copies the overlay over the result, and records everything it did
 * in `conf/security/java-app-customisations.txt`.
 *
 * An ordinary task, one per target: a build can add its own steps with
 * `tasks.withType<CustomiseRuntime>().configureEach { doLast { … } }`, working on [outputDir].
 */
@CacheableTask
abstract class CustomiseRuntime
@Inject
constructor(
    private val fs: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtime: DirectoryProperty

    /** File names become keystore aliases, so they are part of the input. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val trustedCertificates: ConfigurableFileCollection

    @get:Input abstract val replaceDefaultTrust: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val propertiesFiles: ConfigurableFileCollection

    @get:Input abstract val properties: MapProperty<String, String>

    /** Security property → values to add to its comma-separated list. */
    @get:Input abstract val appendToLists: MapProperty<String, List<String>>

    @get:Input abstract val lockProperties: Property<Boolean>

    @get:Input abstract val forbiddenModules: ListProperty<String>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val overlay: DirectoryProperty

    /** The organisation's runtime policy, applied last so a project can only add to it. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policy: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun customise() {
        val out = outputDir.get().asFile
        // jlink writes some files read-only (the legal notices), which an in-place sync cannot
        // overwrite on the next build: start empty, and keep the copies writable by their owner.
        fs.delete { delete(out) }
        fs.copy {
            from(runtime)
            into(out)
            AppCopySpecs.ownerWritable(this)
        }
        val present = ForbiddenModules.present(
            Files.readString(out.resolve("release").toPath()),
            forbiddenModules.get(),
        )
        if (present.isNotEmpty()) throw GradleException(ForbiddenModules.message(present))
        val record = listOf(HEADER) + trust(out) + applySecurityProperties(out) + copyOverlay(out) + applyPolicy(out)
        Files.writeString(out.resolve(RECORD).toPath(), record.joinToString("\n") + "\n")
    }

    private fun trust(out: File): List<String> {
        val files = TrustedCertificates.certificateFiles(trustedCertificates.asFileTree.files)
        val replace = replaceDefaultTrust.get()
        if (replace && files.isEmpty()) {
            throw GradleException(
                "javaApp.security.replaceDefaultTrust is set but no trustedCertificates were found: " +
                    "the runtime would trust nobody",
            )
        }
        if (files.isEmpty()) return emptyList()
        val lines = TrustedCertificates.install(out.resolve("lib/security/cacerts"), files, replace)
        val how = if (replace) "replaced the JDK's CAs with" else "added"
        return listOf("", "trusted certificates: $how ${lines.size}") + lines
    }

    private fun applySecurityProperties(out: File): List<String> {
        val file = out.resolve("conf/security/java.security").toPath()
        val policy =
            SecurityPolicy(
                files = propertiesFiles.files.map(::load),
                properties = properties.get(),
                additions = appendToLists.get(),
                lock = lockProperties.get(),
            )
        val changes = policy.changes(load(file.toFile()))
        if (changes.isEmpty()) return emptyList()
        Files.writeString(file, SecurityPolicy.block(changes, RECORD_NAME), StandardOpenOption.APPEND)
        return listOf("", "security properties (conf/security/java.security):") +
            changes.map { (k, v) -> "  $k=$v" }
    }

    private fun copyOverlay(out: File): List<String> {
        val dir = overlay.orNull?.asFile ?: return emptyList()
        fs.copy {
            from(dir)
            into(out)
        }
        return listOf("", "overlay: copied ${dir.walk().count { it.isFile }} files from ${dir.name}/")
    }

    /** After everything the project did, so the policy has the last word on every value it sets. */
    private fun applyPolicy(out: File): List<String> {
        val policy = RuntimePolicySource.load(policy.files, fs, archives, temporaryDir.resolve("policy"))
            ?: return emptyList()
        val trusted = trustPolicyCertificates(out, policy)
        val file = out.resolve("conf/security/java.security").toPath()
        val rules = SecurityPolicy(emptyList(), policy.set, policy.append, policy.lockProperties)
        val changes = rules.changes(load(file.toFile()))
        if (changes.isNotEmpty()) {
            Files.writeString(file, SecurityPolicy.block(changes, RECORD_NAME), StandardOpenOption.APPEND)
        }
        return listOf("", "runtime policy ${policy.id} ${policy.version}, sha256 ${policy.digest}:") +
            trusted.map { "  trusts $it" } + changes.map { (k, v) -> "  $k=$v" }
    }

    private fun trustPolicyCertificates(out: File, policy: RuntimePolicy): List<String> {
        val certificates = TrustedCertificates.certificateFiles(policy.certificates.toSet())
        if (certificates.isEmpty()) return emptyList()
        return TrustedCertificates.install(out.resolve("lib/security/cacerts"), certificates, replace = false)
    }

    private fun load(file: File) = Properties().apply { Files.newInputStream(file.toPath()).use(::load) }

    companion object {
        private const val HEADER = "# What io.github.fiftieshousewife.java-app changed in this runtime"
        private const val RECORD_NAME = "java-app-customisations.txt"
        const val RECORD = "conf/security/$RECORD_NAME"
    }
}
