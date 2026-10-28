package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException

/**
 * Tokens a runtime file pattern may use besides Ivy's own: the target's and the version's. Gradle's Ivy
 * repositories know only their own, so the stretch from the first other token to the last is handed to Ivy
 * as `[classifier]`, and each artifact carries it rendered ([render]).
 */
object TargetTokens {
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

    fun render(template: String, target: Target, others: Map<String, String> = emptyMap()): String =
        TOKEN.replace(template) { match ->
            val name = match.groupValues[1]
            forTarget[name]?.invoke(target) ?: others[name] ?: throw GradleException(
                "No value for [$name] in '$template' for $target. Tokens: " +
                    (forTarget.keys + others.keys).joinToString { "[$it]" },
            )
        }
}
