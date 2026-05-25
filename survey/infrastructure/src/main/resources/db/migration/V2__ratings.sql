-- ratings: one row per (user, item) for auth callers; no DB-level dedup for anon.
-- submitted_as is immutable through anonymisation (auth-ratings remain
-- distinguishable from anon-from-inception at export-weighting time, spec §4.1).
-- No correctif text column - the text lives in survey_items.definition (single
-- source of truth, spec §4.1 schema discussion).

CREATE TABLE ratings (
    rating_id        UUID PRIMARY KEY,
    item_id          UUID NOT NULL REFERENCES survey_items(item_id),
    user_id          UUID,
    submitted_as     TEXT NOT NULL CHECK (submitted_as IN ('auth','anon')),
    qualite          SMALLINT NOT NULL CHECK (qualite BETWEEN 1 AND 5),
    difficulte       SMALLINT NOT NULL CHECK (difficulte BETWEEN 1 AND 5),
    flag             TEXT CHECK (flag IS NULL OR flag IN
                       ('hors_sujet','auto_reference','erreur_sens','autre')),
    proposed_item_id UUID REFERENCES survey_items(item_id),
    latency_ms       INTEGER,
    client_meta      JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (submitted_as = 'auth' OR (user_id IS NULL AND proposed_item_id IS NULL))
);

-- Partial unique index - auth dedup only; anon may repeat.
CREATE UNIQUE INDEX ratings_auth_uniq
    ON ratings (item_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ratings_item_idx ON ratings (item_id);
CREATE INDEX ratings_user_idx ON ratings (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ratings_submitted_as_idx ON ratings (submitted_as);
