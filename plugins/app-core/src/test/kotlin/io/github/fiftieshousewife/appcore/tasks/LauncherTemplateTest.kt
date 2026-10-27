package io.github.fiftieshousewife.appcore.tasks

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Windows launcher appends `%ARGS%` straight after the main class or script
 * (`Main%ARGS%`, `"main.js"%ARGS%`), so every value it builds must start with a space, or the first
 * argument is glued on: `Main--healthcheck`. CI's Windows leg is the only place the launcher runs,
 * so the contract is checked here too.
 */
class LauncherTemplateTest {
    private val template =
        LauncherTemplateTest::class.java
            .getResource(
                "/io/github/fiftieshousewife/appcore/launcher/launcher.bat",
            )!!
            .readText()

    @Test
    fun `every value the Windows launcher puts in ARGS starts with a space`() {
        val assignments = Regex("""set "?ARGS=([^"\r\n]*)""").findAll(template).map { it.groupValues[1] }.toList()
        assertTrue(assignments.size >= 3, "expected the reset, collect and healthcheck assignments in:\n$template")
        assignments.filter(String::isNotEmpty).forEach { value ->
            assertTrue(
                value.startsWith(" ") || value.startsWith("%ARGS% "),
                "ARGS=$value must start with a space, or it is glued to the main class",
            )
        }
    }
}
