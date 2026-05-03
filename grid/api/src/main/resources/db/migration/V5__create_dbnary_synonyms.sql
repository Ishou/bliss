-- Phase 1 (clue-generation pipeline) §1 — DBnary synonym graph.
-- Mirror of grid/worker/src/main/resources/db/migration/V5__create_dbnary_synonyms.sql.
--
-- DBnary attaches `dbnary:synonym` to LexicalEntry (the word level), not
-- per-sense — same shape we observed querying the fra/ graph. Each row is
-- (entry, synonym_lemma) where synonym_lemma is the canonical form of the
-- synonym (DBnary models synonyms as references to other dbnary:Page nodes,
-- whose label IS the lemma).
--
-- Composite PK doubles as the dedup constraint — re-ingest is idempotent.

CREATE TABLE dbnary_synonyms (
    dbnary_word_id    UUID        NOT NULL REFERENCES dbnary_words(id) ON DELETE CASCADE,
    synonym_lemma     TEXT        NOT NULL CHECK (length(synonym_lemma) >= 1),
    PRIMARY KEY (dbnary_word_id, synonym_lemma)
);

CREATE INDEX dbnary_synonyms_word ON dbnary_synonyms (dbnary_word_id);
