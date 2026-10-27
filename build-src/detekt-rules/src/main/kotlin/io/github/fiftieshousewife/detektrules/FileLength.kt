package io.github.fiftieshousewife.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtFile

/** detekt's LargeClass counts one class's code; a file of several classes and long comments escapes it. */
class FileLength(config: Config) :
    Rule(config, "A file holds at most allowedLines non-blank lines; split a longer one along its seams.") {
    private val allowedLines: Int by config(DEFAULT_ALLOWED_LINES)

    override fun visitKtFile(file: KtFile) {
        val lines = SourceLines.of(file.text)
        if (lines.nonBlank > allowedLines) {
            report(Finding(Entity.from(file), "${lines.nonBlank} non-blank lines, more than $allowedLines"))
        }
    }

    private companion object {
        const val DEFAULT_ALLOWED_LINES = 200
    }
}
