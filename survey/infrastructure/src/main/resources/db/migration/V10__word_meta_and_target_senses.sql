-- ADR-0061: per-rating target senses + per-lemma sub-tag taxonomy & sense inventory.
-- Pure-expand. Existing rows carry empty arrays; no backfill or contract phase.

ALTER TABLE ratings
    ADD COLUMN target_senses JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(target_senses) = 'array');

CREATE TABLE survey_word_meta (
    mot              TEXT PRIMARY KEY,
    sub_tags         JSONB NOT NULL DEFAULT '[]'::jsonb
                       CHECK (jsonb_typeof(sub_tags) = 'array'),
    sense_inventory  JSONB NOT NULL DEFAULT '[]'::jsonb
                       CHECK (jsonb_typeof(sense_inventory) = 'array'),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
