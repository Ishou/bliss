-- user_progress: per-user rating cursor + rolling calibration agreement.
-- Auth-only; anon raters have no row.

CREATE TABLE user_progress (
    user_id              UUID PRIMARY KEY,
    items_rated          INTEGER NOT NULL DEFAULT 0,
    calibration_agreement NUMERIC(4,3),
    last_rated_at        TIMESTAMPTZ
);
