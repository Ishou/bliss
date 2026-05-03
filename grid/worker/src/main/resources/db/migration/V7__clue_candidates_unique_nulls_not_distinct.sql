-- Phase 2 §3 of the clue-generation pipeline plan — rename the clue_candidates
-- UNIQUE constraint from the Postgres auto-generated name to a stable identifier.
--
-- Mirror of grid/worker/src/main/resources/db/migration/V7__clue_candidates_unique_nulls_not_distinct.sql.
--
-- V6's inline UNIQUE clause generated the auto-named constraint
-- `clue_candidates_word_id_source_sense_index_clue_text_key`.
-- Rename it to a stable identifier so monitoring, error messages,
-- and future migrations can reference it reliably.
-- NULLS NOT DISTINCT semantics are unchanged from V6.

ALTER TABLE clue_candidates
    DROP CONSTRAINT clue_candidates_word_id_source_sense_index_clue_text_key;

ALTER TABLE clue_candidates
    ADD CONSTRAINT clue_candidates_word_source_sense_text_uniq
    UNIQUE NULLS NOT DISTINCT (word_id, source, sense_index, clue_text);
