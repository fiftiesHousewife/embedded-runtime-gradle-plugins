package io.github.fiftieshousewife.appcore

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.time.Duration

/**
 * Where and how `appInstall` installs: `javaApp { install { … } }`, `nodeApp { install { … } }`,
 * or `appDeploy { … }` in a deployment build. See [InstallLayout] for the layout.
 */
abstract class InstallSettings {
    /** The install root. Defaults to `-P<javaApp|nodeApp|appDeploy>.installDir`, else `build/install`. */
    abstract val dir: DirectoryProperty

    /** Releases to keep, including `current` and `previous`. Default 3. */
    abstract val keep: Property<Int>

    /**
     * Run after `current` is switched (and after a rollback), so the supervisor starts the new
     * release: `restartCommand("systemctl", "--user", "restart", "orders")`. Default: none.
     */
    abstract val restartCommand: ListProperty<String>

    /** How long the new release has to turn healthy before it is switched to. Default 60 s. */
    abstract val healthTimeout: Property<Duration>

    /**
     * The runtime policy every installed release must comply with. Defaults to
     * `-P<block>.securityPolicy` / `-P<block>.securityPolicyArtifact`; see [RuntimePolicySource].
     */
    abstract val requiredPolicy: ConfigurableFileCollection

    fun restartCommand(vararg command: String) = restartCommand.set(command.toList())

    /** [property] (a Gradle property) names the install root; else `build/install`. */
    internal fun applyConventions(project: Project, property: String) {
        // Resolved against the directory, never the Project, which the configuration cache can't hold.
        val projectDir = project.layout.projectDirectory
        dir.convention(
            project.providers.gradleProperty(property).map { projectDir.dir(it) }
                .orElse(project.layout.buildDirectory.dir("install")),
        )
        keep.convention(DEFAULT_KEEP)
        restartCommand.convention(emptyList())
        healthTimeout.convention(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
    }

    private companion object {
        const val DEFAULT_KEEP = 3
        const val DEFAULT_TIMEOUT_SECONDS = 60L
    }
}
