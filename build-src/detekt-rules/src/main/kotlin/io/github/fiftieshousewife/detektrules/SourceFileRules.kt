package io.github.fiftieshousewife.detektrules

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/** The `source-file` rule set: limits detekt doesn't have, set in config/detekt/detekt.yml. */
class SourceFileRules : RuleSetProvider {
    override val ruleSetId = RuleSetId("source-file")

    override fun instance() = RuleSet(ruleSetId, listOf(::FileLength, ::CommentDensity))
}
