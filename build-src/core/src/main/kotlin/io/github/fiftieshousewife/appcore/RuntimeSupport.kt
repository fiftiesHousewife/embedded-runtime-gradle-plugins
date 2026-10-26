package io.github.fiftieshousewife.appcore

import groovy.json.JsonSlurper
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One release line of a runtime as endoflife.date describes it: `21` of `eclipse-temurin`, say. */
internal data class SupportedRelease(
    val name: String,
    val isEndOfLife: Boolean,
    val endOfLife: LocalDate?,
    val latest: String?,
)

/** What [RuntimeSupport.assess] found: problems fail the check, warnings are only reported. */
internal data class SupportAssessment(val summary: String, val problems: List<String>, val warnings: List<String>)

/**
 * Whether an embedded runtime is still supported, from the endoflife.date API
 * (`/api/v1/products/<product>`): its release line must not have reached end of life, and should
 * not be about to, and the pinned version should be the line's latest patch.
 */
internal object RuntimeSupport {
    const val DEFAULT_URL = "https://endoflife.date/api/v1/products/"
    const val DEFAULT_WARNING_DAYS = 90

    @Suppress("UNCHECKED_CAST")
    fun parse(json: String): List<SupportedRelease> {
        val result = (JsonSlurper().parseText(json) as Map<String, Any?>)["result"] as Map<String, Any?>
        return (result["releases"] as List<Map<String, Any?>>).map { release ->
            SupportedRelease(
                name = release["name"].toString(),
                isEndOfLife = release["isEol"] == true,
                endOfLife = (release["eolFrom"] as String?)?.let(LocalDate::parse),
                latest = (release["latest"] as Map<String, Any?>?)?.get("name")?.toString(),
            )
        }
    }

    fun assess(
        runtime: EmbeddedRuntime,
        releases: List<SupportedRelease>,
        today: LocalDate,
        warningDays: Int,
    ): SupportAssessment {
        val label = "${runtime.product} ${runtime.release}"
        val line = releases.firstOrNull { it.name == runtime.release }
            ?: return SupportAssessment(
                "$label: not listed",
                listOf(
                    "endoflife.date lists no release ${runtime.release} of ${runtime.product}: " +
                        "listed are ${releases.joinToString { it.name }}",
                ),
                emptyList(),
            )
        val supported = releases.filterNot { it.isEndOfLife }.joinToString { it.name }
        val until = line.endOfLife?.let { "supported until $it" } ?: "no end of life announced"
        return SupportAssessment(
            "$label (${runtime.version}): $until, latest ${line.latest ?: "unknown"}",
            listOfNotNull(endOfLife(label, line, supported)),
            listOfNotNull(
                line.endOfLife
                    ?.takeUnless { line.isEndOfLife }
                    ?.takeIf { ChronoUnit.DAYS.between(today, it) <= warningDays }
                    ?.let {
                        "$label reaches end of life on $it, in ${ChronoUnit.DAYS.between(today, it)} days; " +
                            "plan the move to $supported"
                    },
                newerPatch(label, line, runtime),
            ),
        )
    }

    private fun endOfLife(label: String, line: SupportedRelease, supported: String) = if (line.isEndOfLife) {
        "$label reached end of life${line.endOfLife?.let { " on $it" }.orEmpty()} and gets no more " +
            "security fixes; move to a supported release ($supported)"
    } else {
        null
    }

    private fun newerPatch(label: String, line: SupportedRelease, runtime: EmbeddedRuntime) =
        line.latest?.takeIf { isNewer(it, runtime.version) }?.let {
            "$label has a newer release, $it, than the embedded ${runtime.version}; " +
                "upgrade the plugin or override the pin"
        }

    /**
     * Compares the numbers in two versions in order (`21.0.13+9` against `21.0.12.1+1`), which
     * is how every vendor's scheme sorts, whatever separators it uses.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(current)
        val differing = a.zip(b).firstOrNull { (x, y) -> x != y }
        return differing?.let { (x, y) -> x > y } ?: (a.size > b.size)
    }

    private fun numbers(version: String) = Regex("\\d+").findAll(version).map { it.value.toLong() }.toList()
}

/**
 * The runtime an archive embeds, named as endoflife.date names it: [product] `eclipse-temurin` or
 * `nodejs`, release line [release] `21` or `24`, and the exact [version] pinned.
 */
data class EmbeddedRuntime(val product: String, val release: String, val version: String) : java.io.Serializable
