-- Phase 2 §3 of the clue-generation pipeline plan — make the clue_candidates
-- UNIQUE constraint treat NULL sense_index as equal so re-running idempotent
-- derivations (synonym-derived, model-generated lemma clues) does not create
-- duplicates.
--
-- Mirror of grid/worker/src/main/resources/db/migration/V7__clue_candidates_unique_nulls_not_distinct.sql.
--
-- Default Postgres semantics (NULLS DISTINCT) treat each NULL as a unique
-- value, so (word_id, 'dbnary-synonym', NULL, 'Bagnole') inserted twice
-- creates two rows. NULLS NOT DISTINCT (Postgres 15+) collapses them, which
-- is what we need for idempotent re-derivation.
--
-- V6 created the constraint with the auto-generated name
-- `clue_candidates_word_id_source_sense_index_clue_text_key`; we drop and
-- re-create under a stable name. Postgres 16+.

ALTER TABLE clue_candidates
    DROP CONSTRAINT clue_candidates_word_id_source_sense_index_clue_text_key;

ALTER TABLE clue_candidates
    ADD CONSTRAINT clue_candidates_word_source_sense_text_uniq
    UNIQUE NULLS NOT DISTINCT (word_id, source, sense_index, clue_text);
