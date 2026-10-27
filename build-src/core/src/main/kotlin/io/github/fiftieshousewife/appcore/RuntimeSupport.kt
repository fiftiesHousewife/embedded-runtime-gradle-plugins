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
 * From endoflife.date: the release line must not have reached end of life, and should not be about to, and
 * the pinned version should be the line's latest patch.
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
        // Where to move to: never the line itself, which a warning before its end of life would otherwise name.
        val supported = releases.filterNot { it.isEndOfLife || it.name == line.name }.joinToString { it.name }
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
     * By the numbers in order, as every vendor's scheme sorts whatever its separators; the build after `+` comes
     * last, so `21.0.13.1+1` is newer than `21.0.13+9`, and missing numbers count as zero.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val byVersion = compare(numbers(candidate.substringBefore('+')), numbers(current.substringBefore('+')))
        return if (byVersion != 0) {
            byVersion > 0
        } else {
            compare(numbers(candidate.substringAfter('+', "")), numbers(current.substringAfter('+', ""))) > 0
        }
    }

    private fun compare(a: List<Long>, b: List<Long>): Int = (0 until maxOf(a.size, b.size))
        .map { a.getOrElse(it) { 0L }.compareTo(b.getOrElse(it) { 0L }) }
        .firstOrNull { it != 0 } ?: 0

    private fun numbers(version: String) = Regex("\\d+").findAll(version).map { it.value.toLong() }.toList()
}

/** Named as endoflife.date names it: [product] `eclipse-temurin` or `nodejs`, release line [release] `21` or `24`. */
data class EmbeddedRuntime(val product: String, val release: String, val version: String) : java.io.Serializable
