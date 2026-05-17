-- Identity context: player accounts (ADR-0044, ADR-0045).
--
-- display_name is the player-chosen handle (1–30 chars after trim). Not unique
-- by design — no enumeration attack vector (ADR-0045 §3).
-- created_at / last_seen_at are TIMESTAMPTZ so the JVM sees Instant via
-- ResultSet.getTimestamp() with UTC calendar.

CREATE TABLE identity_users (
    user_id      UUID        PRIMARY KEY,
    display_name TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);
