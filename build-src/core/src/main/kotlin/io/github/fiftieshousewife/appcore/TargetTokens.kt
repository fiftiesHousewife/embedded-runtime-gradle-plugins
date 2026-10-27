package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.Serializable

/**
 * The tokens a runtime file pattern may use besides Ivy's own (`[organisation]`, `[module]`,
 * `[revision]`, `[artifact]`, `[ext]`, `[type]`):
 *
 * - the target's, for `linux-x64`:
 *
 *   | Token | Gives | Named as |
 *   |---|---|---|
 *   | `[target]`, `[classifier]` | `linux-x64` | target ids and Maven classifiers |
 *   | `[os]` | `linux` (`windows`, `mac`) | target ids |
 *   | `[arch]` | `x64` (`aarch64`) | target ids, most JDK vendors |
 *   | `[rpmArch]` | `x86_64` (`aarch64`) | RPM, YUM and `uname -m` |
 *   | `[containerArch]` | `amd64` (`arm64`) | container platforms, Debian |
 *
 * - the version's, which the caller passes in (a JDK's `[feature]`, `[update]`, …);
 * - the organisation's own, with a value per target or per OS ([CustomTokens]): `[plat]` as `l64`
 *   on Linux and `w64` on Windows.
 *
 * Gradle's Ivy repositories know only their own tokens, so the stretch of a pattern from its first
 * other token to its last is handed to Ivy as `[classifier]`, and each artifact carries that stretch
 * rendered for it ([render]).
 */
object TargetTokens {
    /** The tokens Ivy fills in itself. */
    private val IVY = setOf("organisation", "module", "revision", "artifact", "ext", "type")

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
     * [pattern] as Ivy takes it, with the stretch holding tokens Ivy doesn't know replaced by
     * `[classifier]`, and that stretch; the stretch is null when the pattern uses Ivy's tokens only.
     */
    fun split(pattern: String): Pair<String, String?> {
        val tokens = TOKEN.findAll(pattern).filter { it.groupValues[1] !in IVY }.toList()
        if (tokens.isEmpty()) return pattern to null
        val start = tokens.first().range.first
        val end = tokens.last().range.last + 1
        val stretch = pattern.substring(start, end)
        if ('(' in stretch || ')' in stretch) {
            throw GradleException(
                "'$pattern': the tokens Ivy doesn't know, from the first to the last, can't reach across " +
                    "the edge of an optional (…) part",
            )
        }
        return pattern.substring(0, start) + "[classifier]" + pattern.substring(end) to stretch
    }

    /**
     * [template] with every token filled in: the target's for [target], then [others] (Ivy's own,
     * the version's, the organisation's). A token with no value fails, naming the ones there are.
     */
    fun render(template: String, target: Target, others: Map<String, String> = emptyMap()): String =
        TOKEN.replace(template) { match ->
            val name = match.groupValues[1]
            forTarget[name]?.invoke(target) ?: others[name] ?: throw GradleException(
                "No value for [$name] in '$template' for $target. Tokens: " +
                    (forTarget.keys + others.keys).joinToString { "[$it]" },
            )
        }
}

/**
 * Tokens of the organisation's own, each with a value per target id or per OS: `[plat]` as `l64`
 * for `linux` and `w64` for `windows`. Set as `<prefix><name>.<target or os>=<value>`, e.g.
 * `javaApp.jdk.token.plat.linux=l64`; a target's own value wins over its OS's.
 */
data class CustomTokens(val values: Map<String, Map<String, String>>) : Serializable {
    fun forTarget(target: Target): Map<String, String> = values.mapNotNull { (name, byTarget) ->
        (byTarget[target.id] ?: byTarget[target.os.id])?.let { name to it }
    }.toMap()

    companion object {
        private const val serialVersionUID = 1L

        /** From settings named `<prefix><name>.<target or os>`, as [settingsPrefixedBy] gives them. */
        fun parse(settings: Map<String, String>, prefix: String) = CustomTokens(
            settings.filterKeys {
                it.startsWith(prefix) &&
                    it.count { c -> c == '.' } >= prefix.count { c -> c == '.' } + 1
            }
                .entries.groupBy({ it.key.removePrefix(prefix).substringBefore('.') }) {
                    it.key.removePrefix(prefix).substringAfter('.') to it.value
                }
                .mapValues { (_, pairs) -> pairs.toMap() },
        )
    }
}
