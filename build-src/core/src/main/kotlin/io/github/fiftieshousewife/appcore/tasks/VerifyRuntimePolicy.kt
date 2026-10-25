package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.RuntimePolicyCheck
import io.github.fiftieshousewife.appcore.RuntimePolicySource
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * `appVerifyPolicy<Target>`: checks an assembled app's real files against the organisation's
 * runtime policy, so no archive that breaks it is ever built, whatever the project configured.
 */
@CacheableTask
abstract class VerifyRuntimePolicy
@Inject
constructor(
    private val fs: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policy: ConfigurableFileCollection

    @get:OutputFile abstract val resultFile: RegularFileProperty

    init {
        onlyIf("a runtime policy is configured") { !policy.isEmpty }
    }

    @TaskAction
    fun verify() {
        val policy = RuntimePolicySource.load(policy.files, fs, archives, temporaryDir.resolve("policy"))
            ?: return
        val app = appDirectory.get().asFile
        val violations = RuntimePolicyCheck.violations(policy, app)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "${app.name} breaks runtime policy ${policy.label}:\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
        resultFile.get().asFile.writeText("compliant with ${policy.label}\n")
    }
}
