-- Phase 2 of the clue-generation pipeline plan — `clue_candidates` table.
-- Mirror of grid/worker/src/main/resources/db/migration/V6__create_clue_candidates.sql.
--
-- One row per candidate clue for a word. Each row is annotated with its
-- `source` (curated / dbnary-synonym / mistral-7b-base / mistral-7b-lora-v1 / ...)
-- so the export pipeline can pick by priority. Multi-clue per word is enabled
-- by design: the UNIQUE key allows several candidates per (word, source) when
-- they target different senses or ship different text.
--
-- ADR-0023 reminder: rows here that originated from DBnary text MUST be
-- LLM-generated CLUES, not DBnary's own `definition_text` or `synonym_lemma`.
-- The synonym-derived path produces a clue STRING from the synonym (e.g.
-- "bagnole" -> the candidate's clue_text), but Phase 3 will rewrite even those
-- through the model first. No raw DBnary text leaks here.

CREATE TABLE clue_candidates (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    word_id       UUID         NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    sense_index   INT          CHECK (sense_index IS NULL OR sense_index >= 0),
    clue_text     TEXT         NOT NULL CHECK (length(clue_text) BETWEEN 1 AND 80),
    source        TEXT         NOT NULL CHECK (length(source) >= 1),
    confidence    NUMERIC      CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    model_version TEXT,
    generated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (word_id, source, sense_index, clue_text)
);

-- The export-time pick reads (word_id, source) to find the highest-priority
-- candidate per word. Same index covers "give me every candidate for word X"
-- and "how many dbnary-synonym candidates exist".
CREATE INDEX clue_candidates_word_source ON clue_candidates (word_id, source);
