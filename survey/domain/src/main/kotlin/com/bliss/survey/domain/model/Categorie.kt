package com.bliss.survey.domain.model

// 18 broad answer classes + AUTRE; finer distinctions live in per-rating sub-tags, not here.
enum class Categorie {
    PERSONNE,
    FAUNE_FLORE,
    GEOGRAPHIE,
    METEO,
    OBJET,
    NOURRITURE,
    CORPS,
    CULTURE,
    HISTOIRE,
    JEU,
    SPORT,
    RELIGION,
    SOCIETE,
    SCIENCE,
    CONCEPTUEL,
    LANGUE,
    ACTION,
    QUALIFICATIF,
    AUTRE,
}
