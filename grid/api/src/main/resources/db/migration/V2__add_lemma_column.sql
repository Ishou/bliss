-- ADR-0014 §2 — add `lemma` column for lemma↔inflection relationship.
-- Mirror of grid/worker/src/main/resources/db/migration/V2__add_lemma_column.sql.
-- Expand step (ADR-0013 §6, ADR-0014 §2): nullable; backfill happens at import.
-- For self-lemma rows, lemma = word. NULL means "lemma unknown" (legacy/manual rows).

ALTER TABLE words ADD COLUMN lemma TEXT;

-- Lookup index for the export-time JOIN that propagates a lemma's clue to its inflections.
CREATE INDEX words_lang_lemma ON words (language, lemma);
