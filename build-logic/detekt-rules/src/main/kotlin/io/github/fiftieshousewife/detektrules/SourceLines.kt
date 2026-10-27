package io.github.fiftieshousewife.detektrules

/** Line counts of one source file, as the size and comment rules measure it. */
data class SourceLines(val nonBlank: Int, val comments: Int) {
    val commentShare: Double get() = if (nonBlank == 0) 0.0 else comments.toDouble() / nonBlank

    companion object {
        /** Counts [text]'s non-blank lines, and those that are only comment: `//`, `/* … */` or KDoc. */
        fun of(text: String): SourceLines {
            val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
            return SourceLines(lines.size, commentLines(lines))
        }

        private fun commentLines(lines: List<String>): Int =
            lines.fold(0 to false) { (count, inBlock), line ->
                when {
                    inBlock -> (count + 1) to !line.contains("*/")
                    line.startsWith("/*") -> (count + 1) to !line.contains("*/")
                    line.startsWith("//") -> (count + 1) to false
                    else -> count to false
                }
            }.first
    }
}
