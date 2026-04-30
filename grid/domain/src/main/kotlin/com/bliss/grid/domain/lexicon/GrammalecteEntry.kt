package com.bliss.grid.domain.lexicon

/** A single parsed entry from a Grammalecte lexique: surface form + lemma + occurrence count. */
data class GrammalecteEntry(
    val word: String,
    val lemma: String,
    val occurrences: Long,
)

/**
 * Parse a Grammalecte `lexique-grammalecte-fr-vX.txt`. Skips comments and the corpus
 * header. For each surface form (`Flexion`), keeps the row with the highest
 * `Total occurrences` — when two POS analyses exist for the same form (e.g. "la" as
 * nom vs det), the dominant one wins, which is also the one a crossword solver
 * expects to recognise.
 *
 * Returns surface form → entry. Caller is expected to feed the values into a writer.
 */
fun parseGrammalecteLexique(lines: Sequence<String>): Map<String, GrammalecteEntry> {
    val best = HashMap<String, GrammalecteEntry>(600_000)
    var fIdx = -1
    var lIdx = -1
    var occIdx = -1
    var headerSeen = false
    for (raw in lines) {
        if (raw.isEmpty() || raw.startsWith("#")) continue
        val cols = raw.split('\t')
        if (!headerSeen) {
            if (cols.firstOrNull() == "id" && "Flexion" in cols) {
                fIdx = cols.indexOf("Flexion")
                lIdx = cols.indexOf("Lemme")
                occIdx = cols.indexOf("Total occurrences")
                require(fIdx >= 0 && lIdx >= 0 && occIdx >= 0) {
                    "lexique header missing required columns: $cols"
                }
                headerSeen = true
            }
            continue
        }
        if (cols.size <= occIdx) continue
        val flexion = cols[fIdx]
        if (!isAcceptable(flexion)) continue
        val word = flexion.lowercase()
        val lemma = cols[lIdx].lowercase()
        val occurrences = cols[occIdx].toLongOrNull() ?: 0L
        val candidate = GrammalecteEntry(word, lemma, occurrences)
        val existing = best[word]
        if (existing == null || candidate.occurrences > existing.occurrences) {
            best[word] = candidate
        }
    }
    return best
}
