package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word

class InMemoryWordRepository(private val words: List<Word>) : WordRepository {

    override fun findByLength(length: Int): List<Word> =
        words.filter { it.text.length == length }

    override fun findByLengthAndPattern(length: Int, pattern: Map<Int, Char>): List<Word> =
        words.filter { word ->
            word.text.length == length && pattern.all { (i, ch) -> word.text[i] == ch }
        }

    companion object {
        fun defaultFrench(): InMemoryWordRepository = InMemoryWordRepository(DEFAULT_FRENCH_WORDS)
    }
}

private val DEFAULT_FRENCH_WORDS: List<Word> = listOf(
    Word("LE", "article defini masculin"),
    Word("LA", "article defini feminin"),
    Word("EN", "preposition"),
    Word("OR", "metal precieux"),
    Word("OS", "anatomie"),
    Word("AS", "carte a jouer"),
    Word("DU", "article contracte"),
    Word("ET", "conjonction"),
    Word("UN", "article indefini"),
    Word("ON", "pronom"),
    Word("AIR", "atmosphere"),
    Word("EAU", "liquide vital"),
    Word("FEU", "combustion"),
    Word("RUE", "voie urbaine"),
    Word("SOL", "surface au sol"),
    Word("VIE", "existence"),
    Word("AMI", "compagnon"),
    Word("FIL", "ligne fine"),
    Word("BOL", "recipient"),
    Word("MUR", "paroi"),
    Word("NEZ", "organe olfactif"),
    Word("PIE", "oiseau noir et blanc"),
    Word("ROI", "souverain"),
    Word("ANE", "equide a longues oreilles"),
    Word("AMER", "qui a un gout apre"),
    Word("BLEU", "couleur du ciel"),
    Word("CHAT", "felin domestique"),
    Word("DOUX", "agreable au toucher"),
    Word("FILM", "oeuvre cinematographique"),
    Word("GARE", "station de train"),
    Word("LIRE", "dechiffrer un texte"),
    Word("MAIN", "extremite du bras"),
    Word("NUIT", "obscurite"),
    Word("PAIN", "aliment de farine"),
    Word("ROSE", "fleur a epines"),
    Word("SAGE", "raisonnable"),
    Word("TIGE", "pousse vegetale"),
    Word("VENT", "air en mouvement"),
    Word("YEUX", "organes de la vue"),
    Word("ZERO", "chiffre nul"),
    Word("CHIEN", "canide domestique"),
    Word("CHOSE", "objet quelconque"),
    Word("FLEUR", "partie odorante d'une plante"),
    Word("LIVRE", "ouvrage relie"),
    Word("PORTE", "ouverture murale"),
    Word("ROUGE", "couleur du sang"),
    Word("TABLE", "meuble plat"),
    Word("VERRE", "recipient transparent"),
    Word("ARBRE", "vegetal a tronc"),
    Word("MONDE", "univers"),
)
