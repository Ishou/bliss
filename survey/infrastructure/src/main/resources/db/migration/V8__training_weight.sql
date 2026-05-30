-- DEFAULT 1.0 (neutral multiplier) keeps this additive with no backfill; survives GDPR erasure as a bare numeric is not PII.
ALTER TABLE survey_items
    ADD COLUMN training_weight NUMERIC NOT NULL DEFAULT 1.0
        CHECK (training_weight > 0);

-- durable cache of cross-context role state; this table IS PII (user_id -> role) and is erased on UserDeleted.
CREATE TABLE maintainer_roles (
    user_id    UUID PRIMARY KEY,
    role       TEXT NOT NULL CHECK (role IN ('player', 'maintainer')),
    changed_at TIMESTAMPTZ NOT NULL
);
