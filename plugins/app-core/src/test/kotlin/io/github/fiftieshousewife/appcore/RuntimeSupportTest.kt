package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RuntimeSupportTest {
    /** The shape of https://endoflife.date/api/v1/products/eclipse-temurin, cut down. */
    private val temurin =
        RuntimeSupport.parse(
            """
            {"schema_version": "1.2.0", "result": {"name": "eclipse-temurin", "releases": [
              {"name": "25", "isEol": false, "eolFrom": "2031-09-30", "latest": {"name": "25.0.5+8"}},
              {"name": "21", "isEol": false, "eolFrom": "2029-12-31", "latest": {"name": "21.0.13+9"}},
              {"name": "17", "isEol": false, "eolFrom": "2027-01-15", "latest": {"name": "17.0.21+7"}},
              {"name": "11", "isEol": false, "eolFrom": null, "latest": null},
              {"name": "8", "isEol": true, "eolFrom": "2026-03-31", "latest": {"name": "8u482-b08"}}
            ]}}
            """.trimIndent(),
        )
    private val today = LocalDate.of(2026, 10, 26)

    private fun assess(release: String, version: String) =
        RuntimeSupport.assess(EmbeddedRuntime("eclipse-temurin", release, version), temurin, today, 90)

    @Test
    fun `a supported release on its latest patch passes quietly`() {
        val result = assess("21", "21.0.13+9")
        assertAll(
            { assertEquals(emptyList<String>(), result.problems) },
            { assertEquals(emptyList<String>(), result.warnings) },
            {
                assertEquals(
                    "eclipse-temurin 21 (21.0.13+9): supported until 2029-12-31, latest 21.0.13+9",
                    result.summary,
                )
            },
        )
    }

    @Test
    fun `a newer patch is a warning`() {
        val result = assess("21", "21.0.12.1+1")
        assertAll(
            { assertEquals(emptyList<String>(), result.problems) },
            { assertTrue(result.warnings.single().contains("newer release, 21.0.13+9"), result.warnings.toString()) },
        )
    }

    @Test
    fun `a release past its end of life is a problem that names the supported ones`() {
        val problem = assess("8", "8u482-b08").problems.single()
        assertAll(
            { assertTrue(problem.contains("reached end of life on 2026-03-31"), problem) },
            { assertTrue(problem.contains("(25, 21, 17, 11)"), problem) },
        )
    }

    @Test
    fun `an end of life within the warning period is a warning`() {
        val warning = assess("17", "17.0.21+7").warnings.single()
        assertAll(
            { assertTrue(warning.contains("reaches end of life on 2027-01-15, in 81 days"), warning) },
            { assertTrue(warning.endsWith("plan the move to 25, 21, 11"), warning) },
        )
    }

    @Test
    fun `a release with no end of life or latest version announced passes`() {
        val result = assess("11", "11.0.32.1+1")
        assertAll(
            { assertEquals(emptyList<String>(), result.problems + result.warnings) },
            { assertTrue(result.summary.contains("no end of life announced"), result.summary) },
        )
    }

    @Test
    fun `a release endoflife date does not list is a problem`() {
        assertTrue(assess("27", "27+35").problems.single().contains("lists no release 27"))
    }

    @Test
    fun `versions compare by their numbers in order, whatever the vendor's separators`() {
        assertAll(
            { assertTrue(RuntimeSupport.isNewer("21.0.13+9", "21.0.12.1+1")) },
            { assertTrue(RuntimeSupport.isNewer("24.22.1", "24.18.1")) },
            { assertTrue(RuntimeSupport.isNewer("21.0.13.0.2", "21.0.13")) },
            { assertFalse(RuntimeSupport.isNewer("21.0.13", "21.0.13")) },
            // A point release beats any build of the release before it: the build number comes last.
            { assertTrue(RuntimeSupport.isNewer("21.0.13.1+1", "21.0.13+9")) },
            { assertFalse(RuntimeSupport.isNewer("21.0.12+8", "21.0.12.1+1")) },
            { assertTrue(RuntimeSupport.isNewer("21.0.13+10", "21.0.13+9")) },
            { assertFalse(RuntimeSupport.isNewer("21.52.203", "21.54.107-ca-jdk21.0.13")) },
        )
    }
}
