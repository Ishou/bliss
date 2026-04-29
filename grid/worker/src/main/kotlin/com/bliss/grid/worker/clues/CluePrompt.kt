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

Format de sortie (impératif) :
- Réponds par UNE SEULE LIGNE contenant uniquement la définition finale.
- Pas de raisonnement, pas de comptage à voix haute, pas de listes d'essais, pas de préfixe "Définition :", pas de guillemets, pas d'espaces en début/fin, pas de point final.
- Si tu hésites entre plusieurs formulations, choisis-en une et n'écris que celle-là.

Contrainte de longueur (la plus violée) :
- La définition fait au maximum 18 caractères, espaces compris. Vise 8 à 16 pour garder une marge.
- Si ta première formulation dépasse, raccourcis-la silencieusement (supprime articles, adjectifs, reformule) puis n'écris que la version finale.
- Ne dépasse jamais 18 caractères au total, espaces inclus.

Qualité :
- Utilise du vocabulaire courant qu'un adulte francophone moyen reconnaît immédiatement. Évite les synonymes rares, archaïques ou techniques (ex. ne pas définir "cajoleur" par "celui qui blandice" — préférer "Flatteur").
- Indice évocateur, pas définition de dictionnaire. Suggère, ne paraphrase pas.
- Ne réutilise jamais la racine du mot ni un mot de la même famille. Ne mentionne jamais le mot lui-même.
- Évite le flou ("Chose", "Truc", "Animal") sauf nécessité absolue ; préfère un détail discriminant."""

/**
 * Few-shot examples (ADR-0013 §5). Lengths chosen to span the §2 query window (3–6 letters);
 * styles vary so the model sees both functional ("Tombe du ciel") and definitional-but-terse
 * ("Métal fort") clues.
 */
internal val FEW_SHOT_EXAMPLES: List<Pair<String, String>> =
    listOf(
        // Short words — 3 to 5 letters
        "sel" to "Assaisonne",
        "fer" to "Métal fort",
        "chat" to "Félin domestique",
        "pluie" to "Tombe du ciel",
        // Mid-range — 6 letters
        "oiseau" to "Etre à plumes",
        "soleil" to "Astre du jour",
        // Longer words (7-9 letters) — where the model overran most often
        "fenêtre" to "Trou vitré",
        "musique" to "Art des sons",
        "voiture" to "Quatre-roues",
        "dérision" to "Moquerie",
        "assertion" to "Affirmation",
        "bijection" to "Application 1-1",
    )

/** Per-word user message. The system prompt + few-shot are cached; only this varies per call. */
internal fun userMessage(word: String): String = "Mot : $word"

/** Re-prompt template when the model overruns the 18-char cap. */
internal fun retryMessage(word: String): String =
    "Ta dernière réponse dépassait 18 caractères. Donne une formulation plus courte (vise 8-15). Une seule ligne, sans raisonnement. Mot : $word"

/** Rendered few-shot block (appended to the system prompt's cached prefix). */
internal fun fewShotBlock(): String = FEW_SHOT_EXAMPLES.joinToString(separator = "\n") { (word, clue) -> "Mot : $word -> $clue" }

/** Hard length cap, in characters, including spaces (ADR-0013 §5). */
internal const val MAX_CLUE_CHARS: Int = 18

/** Total attempts (1 initial + 2 retries) before the row is left NULL (ADR-0013 §5). */
internal const val MAX_ATTEMPTS: Int = 3
