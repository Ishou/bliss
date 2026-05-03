-- Phase 1 (clue-generation pipeline) §1 — DBnary lexical-entry table.
-- Mirror of grid/worker/src/main/resources/db/migration/V3__create_dbnary_words.sql.
--
-- Schema choice notes:
--   - id is UUID (matches words.word_id from V1; no extension needed).
--   - (language, lemma, pos) is the natural ingest key: DBnary distinguishes
--     a lemma by its part of speech, so 'pull' the noun and 'pull' the verb
--     would be two rows.
--   - language defaults to 'fr' since the v1 lexicon is French-only; the
--     column is here for the eventual multi-language story (ADR follow-up).

CREATE TABLE dbnary_words (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    lemma           TEXT        NOT NULL,
    pos             TEXT        NOT NULL,
    language        TEXT        NOT NULL DEFAULT 'fr',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (language, lemma, pos)
);

-- Supports "give me every DBnary entry for this lemma" lookups, used by the
-- export-time pipeline when joining against the words table on lemma.
CREATE INDEX dbnary_words_lang_lemma ON dbnary_words (language, lemma);
