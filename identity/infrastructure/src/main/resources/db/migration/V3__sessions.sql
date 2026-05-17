-- identity_sessions: player auth sessions (ADR-0044/ADR-0045).
-- FK ON DELETE CASCADE: removing a user automatically cleans up their sessions.
-- revoked_at is null for active sessions; non-null means the session was invalidated.
-- Index on user_id supports efficient deleteForUser queries.

CREATE TABLE identity_sessions (
    session_id  UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_identity_sessions_user_id ON identity_sessions (user_id);
