package io.github.fiftieshousewife.appcore

import org.gradle.api.provider.Property

/** `runtimeSupport { … }` in `javaApp {}` / `nodeApp {}`: how `checkRuntimeSupport` judges the runtime. */
abstract class RuntimeSupportSettings {
    /** Where the end-of-life data comes from. Defaults to `-P…runtimeSupportUrl`, else endoflife.date. */
    abstract val url: Property<String>

    /** How early to warn of an end of life. Defaults to `-P…endOfLifeWarningDays`, else 90. */
    abstract val warningDays: Property<Int>

    /** Warn instead of failing on an end-of-life runtime. Defaults to `-P…allowEndOfLife`, else false. */
    abstract val allowEndOfLife: Property<Boolean>
}
