package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException

/**
 * The tokens a runtime file pattern may use for the target it serves, besides Ivy's own
 * (`[organisation]`, `[module]`, `[revision]`, `[artifact]`, `[ext]`). For `linux-x64`:
 *
 * | Token | Gives | Named as |
 * |---|---|---|
 * | `[target]`, `[classifier]` | `linux-x64` | target ids and Maven classifiers |
 * | `[os]` | `linux` (`windows`, `mac`) | target ids |
 * | `[arch]` | `x64` (`aarch64`) | target ids, most JDK vendors |
 * | `[rpmArch]` | `x86_64` (`aarch64`) | RPM, YUM and `uname -m` |
 * | `[containerArch]` | `amd64` (`arm64`) | container platforms, Debian |
 *
 * Gradle's Ivy repositories know only their own tokens, so the stretch of a pattern from its first
 * target token to its last is handed to Ivy as `[classifier]`, and each target's artifact carries
 * that stretch rendered for it ([render]).
 */
object TargetTokens {
    private val forTarget: Map<String, (Target) -> String> = linkedMapOf(
        "target" to Target::id,
        "classifier" to Target::id,
        "os" to { it.os.id },
        "arch" to { it.arch.id },
        "rpmArch" to { it.arch.rpm },
        "containerArch" to { it.arch.container },
    )
    private val TOKEN = Regex("""\[([A-Za-z]+)]""")

    /**
     * [pattern] as Ivy takes it, with the stretch holding target tokens replaced by `[classifier]`,
     * and that stretch; the stretch is null when the pattern names no target token.
     */
    fun split(pattern: String): Pair<String, String?> {
        val tokens = TOKEN.findAll(pattern).filter { it.groupValues[1] in forTarget }.toList()
        if (tokens.isEmpty()) return pattern to null
        val start = tokens.first().range.first
        val end = tokens.last().range.last + 1
        val stretch = pattern.substring(start, end)
        if ('(' in stretch || ')' in stretch) {
            throw GradleException(
                "'$pattern': the OS and CPU tokens, from the first to the last, can't reach across " +
                    "the edge of an optional (…) part",
            )
        }
        return pattern.substring(0, start) + "[classifier]" + pattern.substring(end) to stretch
    }

    /** [template] with every target token filled in for [target], and Ivy's own from [others]. */
    fun render(template: String, target: Target, others: Map<String, String> = emptyMap()): String =
        TOKEN.replace(template) { match ->
            val name = match.groupValues[1]
            forTarget[name]?.invoke(target) ?: others[name] ?: match.value
        }
}
