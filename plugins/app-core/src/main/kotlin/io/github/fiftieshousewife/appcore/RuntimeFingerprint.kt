package io.github.fiftieshousewife.appcore

/**
 * What a runtime archive is built from, one sorted line per input. Equal fingerprints should mean equal archives, so
 * equal fingerprints with different archives mean the repository stores one runtime twice.
 */
internal object RuntimeFingerprint {
    fun render(entries: Map<String, String>, files: List<Pair<String, String>>): String =
        (entries.map { (key, value) -> "$key=$value" } + files.map { (path, sha) -> "file $path $sha" })
            .sorted()
            .joinToString("\n", postfix = "\n")

    /** One target's runtime in one release: its fingerprint's lines and its archive's SHA-256. */
    data class Release(val version: String, val fingerprint: Set<String>, val archiveSha256: String)

    sealed interface Verdict {
        data object New : Verdict

        /** The inputs changed, so a new runtime is expected; [lines] are this release's that differ. */
        data class Changed(val lines: List<String>) : Verdict

        data object Unchanged : Verdict

        data object Duplicated : Verdict
    }

    fun compare(previous: Release?, current: Release): Verdict = when {
        previous == null -> Verdict.New

        previous.fingerprint != current.fingerprint ->
            Verdict.Changed((current.fingerprint - previous.fingerprint).sorted())

        previous.archiveSha256 == current.archiveSha256 -> Verdict.Unchanged

        else -> Verdict.Duplicated
    }

    fun describe(target: String, previous: Release?, current: Release): String =
        when (val verdict = compare(previous, current)) {
            Verdict.New -> "$target: no runtime in the previous release"

            is Verdict.Changed ->
                "$target: a new runtime, since ${previous?.version} differs in " +
                    verdict.lines.take(SHOWN).joinToString("; ") + more(verdict.lines.size)

            Verdict.Unchanged -> "$target: the same runtime as ${previous?.version}, stored once"

            Verdict.Duplicated -> duplicated(target, previous, current)
        }

    private fun duplicated(target: String, previous: Release?, current: Release) =
        "$target: the runtime's inputs equal ${previous?.version}'s, but its archive is different bytes " +
            "(${current.archiveSha256} against ${previous?.archiveSha256}), so the repository would store a second " +
            "copy. Something that reaches the archive is not reproducible: compare the two archives' entries"

    private fun more(count: Int) = if (count > SHOWN) " and ${count - SHOWN} more" else ""

    private const val SHOWN = 5

    const val CLASSIFIER_SUFFIX = "-inputs"

    const val EXTENSION = "txt"
}
