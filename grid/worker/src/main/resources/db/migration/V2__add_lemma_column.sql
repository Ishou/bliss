-- ADR-0014 §2 — add `lemma` column for lemma↔inflection relationship.
-- Mirror of grid/api/src/main/resources/db/migration/V2__add_lemma_column.sql.
-- Expand step (ADR-0013 §6, ADR-0014 §2): nullable; backfill happens at import.
-- For self-lemma rows, lemma = word. NULL means "lemma unknown" (legacy/manual rows).

ALTER TABLE words ADD COLUMN lemma TEXT;

-- Supports "find all inflections of a given lemma" queries (WHERE language = ? AND lemma = ?).
-- The export-time JOIN (ON l.language = w.language AND l.word = w.lemma) is already covered
-- by the UNIQUE (word, language) constraint index created in V1 and does not use this index.
CREATE INDEX words_lang_lemma ON words (language, lemma);
