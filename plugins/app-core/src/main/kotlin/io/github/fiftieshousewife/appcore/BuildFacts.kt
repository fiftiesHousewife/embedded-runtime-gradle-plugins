package io.github.fiftieshousewife.appcore

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.time.Duration

/**
 * What a build knows without being told: the commit and source from git, and the choices that belong
 * to a machine or CI agent rather than the app, read from `<javaApp|nodeApp>.<name>` properties.
 */
class BuildFacts(project: Project, private val ecosystem: Ecosystem) {
    private val providers = project.providers
    private val git = Git(project)

    val gitCommit: Provider<String> =
        property("gitCommit").orElse(git.output("rev-parse", "HEAD")).orElse("unknown")

    val gitCommitTime: Provider<String> = git.output("log", "-1", "--format=%cI")

    val sourceUrl: Provider<String> = git.output("remote", "get-url", "origin").map(SourceUrl::fromRemote)

    val runtimeOptions: Provider<RuntimeOptions> =
        property("runtimeOptions").map(RuntimeOptions::parse).orElse(RuntimeOptions.OPEN)

    val smokeTestTimeout: Provider<Duration> =
        property("smokeTestTimeout").map { Duration.ofSeconds(it.toLong()) }.orElse(DEFAULT_SMOKE_TEST_TIMEOUT)

    val allowUntestedPublish: Provider<Boolean> = flag("allowUntestedPublish")

    val runtimeSupportUrl: Provider<String> = property("runtimeSupportUrl").orElse(RuntimeSupport.DEFAULT_URL)

    val endOfLifeWarningDays: Provider<Int> =
        property("endOfLifeWarningDays").map(String::toInt).orElse(RuntimeSupport.DEFAULT_WARNING_DAYS)

    val allowEndOfLife: Provider<Boolean> = flag("allowEndOfLife")

    fun property(name: String): Provider<String> = providers.gradleProperty(ecosystem.property(name))

    private fun flag(name: String) = property(name).map(String::toBoolean).orElse(false)

    companion object {
        val DEFAULT_SMOKE_TEST_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
