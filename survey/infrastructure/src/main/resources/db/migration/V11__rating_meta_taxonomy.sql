-- ADR-0061 (amended): collapse the 48-value Categorie to 18 broad classes + autre,
-- move all meta onto the rating row, and drop the per-lemma word-meta side-table.
-- Pre-alpha, never released: target_senses and survey_word_meta carry no production data.

-- 1. Backfill survey_items.categorie to the collapsed taxonomy (values stored lowercased).
UPDATE survey_items SET categorie = CASE categorie
    WHEN 'first_names' THEN 'personne'
    WHEN 'titles' THEN 'personne'
    WHEN 'mythology' THEN 'personne'
    WHEN 'professions' THEN 'personne'
    WHEN 'famille_relations' THEN 'personne'
    WHEN 'animals' THEN 'faune_flore'
    WHEN 'flore' THEN 'faune_flore'
    WHEN 'cardinal_points' THEN 'geographie'
    WHEN 'cities' THEN 'geographie'
    WHEN 'countries' THEN 'geographie'
    WHEN 'country_codes' THEN 'geographie'
    WHEN 'geography' THEN 'geographie'
    WHEN 'nature_paysage' THEN 'geographie'
    WHEN 'meteo_climat' THEN 'meteo'
    WHEN 'vetements' THEN 'objet'
    WHEN 'mobilier_objet' THEN 'objet'
    WHEN 'outils' THEN 'objet'
    WHEN 'transports' THEN 'objet'
    WHEN 'materiaux' THEN 'objet'
    WHEN 'aliments' THEN 'nourriture'
    WHEN 'body_parts' THEN 'corps'
    WHEN 'senses' THEN 'corps'
    WHEN 'arts' THEN 'culture'
    WHEN 'music_notes' THEN 'culture'
    WHEN 'card_game' THEN 'jeu'
    WHEN 'games' THEN 'jeu'
    WHEN 'currencies' THEN 'societe'
    WHEN 'organizations' THEN 'societe'
    WHEN 'chemical_symbols' THEN 'science'
    WHEN 'units' THEN 'science'
    WHEN 'celestial_objects' THEN 'science'
    WHEN 'nombres' THEN 'science'
    WHEN 'roman_numerals' THEN 'science'
    WHEN 'sentiments_etats' THEN 'conceptuel'
    WHEN 'temps_duree' THEN 'conceptuel'
    WHEN 'couleurs' THEN 'conceptuel'
    WHEN 'abbreviations' THEN 'langue'
    WHEN 'etranger' THEN 'langue'
    WHEN 'expressions' THEN 'langue'
    WHEN 'grammar' THEN 'langue'
    WHEN 'interjections' THEN 'langue'
    WHEN 'orthographe' THEN 'langue'
    -- already-collapsed values map to themselves (idempotent)
    WHEN 'personne' THEN 'personne'
    WHEN 'faune_flore' THEN 'faune_flore'
    WHEN 'geographie' THEN 'geographie'
    WHEN 'meteo' THEN 'meteo'
    WHEN 'objet' THEN 'objet'
    WHEN 'nourriture' THEN 'nourriture'
    WHEN 'corps' THEN 'corps'
    WHEN 'culture' THEN 'culture'
    WHEN 'histoire' THEN 'histoire'
    WHEN 'jeu' THEN 'jeu'
    WHEN 'sport' THEN 'sport'
    WHEN 'religion' THEN 'religion'
    WHEN 'societe' THEN 'societe'
    WHEN 'science' THEN 'science'
    WHEN 'conceptuel' THEN 'conceptuel'
    WHEN 'langue' THEN 'langue'
    WHEN 'action' THEN 'action'
    WHEN 'qualificatif' THEN 'qualificatif'
    ELSE 'autre'
END;

-- 2. Replace per-rating target_senses with the collapsed per-rating meta columns.
ALTER TABLE ratings DROP COLUMN target_senses;
ALTER TABLE ratings
    ADD COLUMN target_categories JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(target_categories) = 'array'),
    ADD COLUMN target_sense TEXT,
    ADD COLUMN is_multisense BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN sub_tags JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(sub_tags) = 'array');

-- 3. Drop the per-lemma word-meta side-table; meta now lives on the rating.
DROP TABLE survey_word_meta;
