-- Phase 1 (clue-generation pipeline) §1 — DBnary sense table.
-- Mirror of grid/worker/src/main/resources/db/migration/V4__create_dbnary_senses.sql.
--
-- Each row is one definition for a DBnary entry, ordered by sense_index
-- (0-based, preserves the order DBnary returned). The sentence-level
-- self-reference filter that ships in scripts/eval/enrich_with_morphology.py
-- walks senses in this order, so preserving it matters for the
-- "first non-self-referential sense" heuristic.
--
-- register is the parenthetical domain/usage marker DBnary embeds inline
-- (e.g. '(Familier)', '(Religion)', '(Vieilli)'); kept separate from
-- definition_text to avoid bleeding into the prompt.

CREATE TABLE dbnary_senses (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    dbnary_word_id    UUID        NOT NULL REFERENCES dbnary_words(id) ON DELETE CASCADE,
    sense_index       INT         NOT NULL CHECK (sense_index >= 0),
    definition_text   TEXT        NOT NULL CHECK (length(definition_text) >= 1),
    register          TEXT        CHECK (register IS NULL OR length(register) >= 1),
    UNIQUE (dbnary_word_id, sense_index)
);

CREATE INDEX dbnary_senses_word ON dbnary_senses (dbnary_word_id);
