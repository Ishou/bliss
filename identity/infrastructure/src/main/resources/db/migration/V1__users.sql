-- identity_users: player accounts (ADR-0044/ADR-0045). display_name intentionally non-unique (no enumeration risk, ADR-0045 §3); timestamps are TIMESTAMPTZ, read via UTC Calendar.

CREATE TABLE identity_users (
    user_id      UUID        PRIMARY KEY,
    display_name TEXT        NOT NULL CHECK (char_length(trim(display_name)) BETWEEN 1 AND 30),
    created_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);
