package io.github.fiftieshousewife.appcore

import org.gradle.api.Project

/**
 * The conventions of [AppExtension]'s settings that describe the app rather than shape the
 * pipeline: its commit, licence and source, and how its runtime's support is judged.
 */
internal class ExtensionConventions(project: Project, private val ecosystem: Ecosystem) {
    private val providers = project.providers
    private val git = Git(project)

    fun apply(extension: AppExtension) {
        extension.gitCommit.convention(
            property("gitCommit").orElse(git.output("rev-parse", "HEAD")).orElse("unknown"),
        )
        extension.license.convention("LicenseRef-Proprietary")
        extension.sourceUrl.convention(git.output("remote", "get-url", "origin").map(SourceUrl::fromRemote))
        extension.runtimeSupport.apply {
            url.convention(property("runtimeSupportUrl").orElse(RuntimeSupport.DEFAULT_URL))
            warningDays.convention(
                property("endOfLifeWarningDays").map(String::toInt).orElse(RuntimeSupport.DEFAULT_WARNING_DAYS),
            )
            allowEndOfLife.convention(property("allowEndOfLife").map(String::toBoolean).orElse(false))
        }
    }

    private fun property(name: String) = providers.gradleProperty(ecosystem.property(name))
}
