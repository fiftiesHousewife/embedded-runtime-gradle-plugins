package io.github.fiftieshousewife.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtFile

/** Comments explain why; when they are a large share of a file they mostly repeat what the code says. */
class CommentDensity(config: Config) :
    Rule(config, "At most allowedPercent of a file's non-blank lines are comments.") {
    private val allowedPercent: Int by config(DEFAULT_ALLOWED_PERCENT)
    private val minLines: Int by config(DEFAULT_MIN_LINES)

    override fun visitKtFile(file: KtFile) {
        val lines = SourceLines.of(file.text)
        val percent = Math.round(lines.commentShare * PERCENT).toInt()
        if (lines.nonBlank >= minLines && percent > allowedPercent) {
            report(Finding(Entity.from(file), "$percent% of the non-blank lines are comments, more than $allowedPercent%"))
        }
    }

    private companion object {
        const val DEFAULT_ALLOWED_PERCENT = 15
        const val DEFAULT_MIN_LINES = 30
        const val PERCENT = 100
    }
}
