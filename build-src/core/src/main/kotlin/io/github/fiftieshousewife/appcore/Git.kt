package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.GitValueSource
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/** Read-only git commands in a project's directory, as configuration-cache inputs. */
internal class Git(private val project: Project) {
    /** The output of `git <arguments>`, absent outside a checkout or without git. */
    fun output(vararg arguments: String): Provider<String> = project.providers.of(GitValueSource::class.java) {
        parameters.workingDirectory.set(project.layout.projectDirectory)
        parameters.arguments.set(arguments.toList())
    }
}
