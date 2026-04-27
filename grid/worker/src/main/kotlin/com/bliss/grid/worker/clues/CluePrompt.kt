// Pinned system prompt + few-shot examples for `generate-clues` (ADR-0013 §5).
// "ADR-class audit trail": any wording change here ships behind a new ADR.
package com.bliss.grid.worker.clues

/**
 * System prompt for the French-clue generator. The ≤18-char hard constraint is stated
 * twice in different words — it is the most-violated rule; repetition is intentional.
 * Output rules match classic French crossword convention: no quotes, no surrounding
 * whitespace, no terminal period.
 */
internal const val SYSTEM_PROMPT: String =
    """Tu es un rédacteur de définitions de mots croisés en français.

Contrainte la plus importante : la définition fait au maximum 18 caractères, espaces compris.
Répétition pour insister : ne dépasse jamais 18 caractères au total, espaces inclus.

Style : court, évocateur, jamais une définition de dictionnaire — c'est un indice, pas une entrée lexicographique.

Format de sortie : uniquement le texte de la définition, sans guillemets, sans espaces en début ou en fin, sans point final."""

/**
 * Few-shot examples (ADR-0013 §5). Lengths chosen to span the §2 query window (3–6 letters);
 * styles vary so the model sees both functional ("Tombe du ciel") and definitional-but-terse
 * ("Métal fort") clues.
 */
internal val FEW_SHOT_EXAMPLES: List<Pair<String, String>> =
    listOf(
        "chat" to "Félin domestique",
        "arbre" to "Pousse en forêt",
        "pluie" to "Tombe du ciel",
        "sel" to "Assaisonne",
        "fer" to "Métal fort",
        "oiseau" to "Etre à plumes",
    )

/** Per-word user message. The system prompt + few-shot are cached; only this varies per call. */
internal fun userMessage(word: String): String = "Mot : $word"

/** Re-prompt template when the model overruns the 18-char cap. */
internal fun retryMessage(word: String): String = "Trop long. Réessaie en respectant strictement ≤18 caractères espaces inclus. Mot : $word"

/** Rendered few-shot block (appended to the system prompt's cached prefix). */
internal fun fewShotBlock(): String = FEW_SHOT_EXAMPLES.joinToString(separator = "\n") { (word, clue) -> "Mot : $word -> $clue" }

/** Hard length cap, in characters, including spaces (ADR-0013 §5). */
internal const val MAX_CLUE_CHARS: Int = 18

/** Total attempts (1 initial + 2 retries) before the row is left NULL (ADR-0013 §5). */
internal const val MAX_ATTEMPTS: Int = 3
