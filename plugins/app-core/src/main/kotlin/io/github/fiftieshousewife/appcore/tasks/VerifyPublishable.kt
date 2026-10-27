package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

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
        if (allowUntested.get()) {
            logger.warn(UntestedArchives.warning(untested, allowUntestedProperty.get()))
        } else {
            throw GradleException(UntestedArchives.refusal(untested, allowUntestedProperty.get()))
        }
    }
}

/** What [VerifyPublishable] says about archives that were never started. */
internal object UntestedArchives {
    fun warning(untested: List<String>, property: String) =
        "Publishing untested archives: ${describe(untested)} never started here ($property=true)"

    fun refusal(untested: List<String>, property: String) =
        "Refusing to publish: ${describe(untested)} never started, because only this machine's archive can be " +
            "smoke-tested here. Publish each from a build on that target (CI), or set -P$property=true " +
            "(or allowUntestedPublish = true) to publish them untested."

    /** `the linux-x64 archive was` / `the linux-x64, windows-x64 archives were`. */
    fun describe(untested: List<String>) =
        "the ${untested.joinToString()} archive" + if (untested.size > 1) "s were" else " was"
}
