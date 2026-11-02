package io.github.fiftieshousewife.appcore

/**
 * Orders `[epoch:]version-release` strings as rpm does (`rpmvercmp`): epoch first, then version, then release,
 * each split into runs of digits and of letters; digits beat letters, longer numbers beat shorter, and a `~`
 * sorts before anything, so `1.0~rc1` is older than `1.0`.
 */
internal object RpmVersions {
    fun compare(a: String, b: String): Int {
        val (epochA, restA) = epoch(a)
        val (epochB, restB) = epoch(b)
        if (epochA != epochB) return epochA.compareTo(epochB)
        val byVersion = segments(restA.substringBeforeLast('-'), restB.substringBeforeLast('-'))
        return if (byVersion != 0) byVersion else segments(release(restA), release(restB))
    }

    private fun epoch(evr: String): Pair<Long, String> =
        if (':' in evr) (evr.substringBefore(':').toLongOrNull() ?: 0L) to evr.substringAfter(':') else 0L to evr

    private fun release(evr: String) = if ('-' in evr) evr.substringAfterLast('-') else ""

    private tailrec fun segments(rawA: String, rawB: String): Int {
        val a = rawA.trimStart(::separator)
        val b = rawB.trimStart(::separator)
        val tilde = tilde(a, b)
        if (tilde != null) return tilde
        if (a.startsWith('~')) return segments(a.drop(1), b.drop(1))
        if (a.isEmpty() || b.isEmpty()) return a.isNotEmpty().compareTo(b.isNotEmpty())
        val x = RUN.find(a)?.value ?: return a.compareTo(b)
        val y = RUN.find(b)?.value ?: return a.compareTo(b)
        val compared = compareSegment(x, y)
        return if (compared != 0) compared else segments(a.drop(x.length), b.drop(y.length))
    }

    /** Older for the one alone in starting with `~`; null when both or neither do. */
    private fun tilde(a: String, b: String): Int? {
        val tildeA = a.startsWith('~')
        return if (tildeA == b.startsWith('~')) {
            null
        } else if (tildeA) {
            -1
        } else {
            1
        }
    }

    private fun separator(c: Char) = !c.isLetterOrDigit() && c != '~'

    private fun compareSegment(a: String, b: String): Int {
        val numeric = a.first().isDigit()
        if (numeric != b.first().isDigit()) return if (numeric) 1 else -1
        if (!numeric) return a.compareTo(b)
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }

    /** A run of digits or of letters, at the start. */
    private val RUN = Regex("^(\\d+|[A-Za-z]+)")
}
