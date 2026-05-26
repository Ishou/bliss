-- survey_items: candidate clues with the 5-axis annotation (style_guide §4 + §7).
-- Polymorphic by `source`; rater-proposed clues carry source='rater_proposed'.
-- Duplicate (mot, definition) is rejected while unretired (V5).

CREATE TABLE survey_items (
    item_id        UUID PRIMARY KEY,
    mot            TEXT NOT NULL,
    definition     TEXT NOT NULL,
    pos            TEXT NOT NULL,
    categorie      TEXT NOT NULL,
    style          TEXT NOT NULL,
    force_claimed  SMALLINT NOT NULL CHECK (force_claimed BETWEEN 1 AND 5),
    longueur       SMALLINT NOT NULL CHECK (longueur > 0),
    source         TEXT NOT NULL,
    source_batch   TEXT NOT NULL,
    tier           TEXT NOT NULL DEFAULT 'mid'
                   CHECK (tier IN ('high','mid','low','excluded')),
    is_calibration BOOLEAN NOT NULL DEFAULT FALSE,
    expected       JSONB,
    retired_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX survey_items_tier_unretired_idx
    ON survey_items (tier) WHERE retired_at IS NULL;
CREATE INDEX survey_items_mot_idx ON survey_items (mot);
CREATE INDEX survey_items_source_idx ON survey_items (source);
