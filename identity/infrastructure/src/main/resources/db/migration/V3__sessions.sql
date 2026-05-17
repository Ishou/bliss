-- Identity context: player sessions (ADR-0044 §5).
--
-- revoked_at is NULL while the session is active; set to a non-null instant
-- when the session is explicitly revoked (logout / account deletion). A partial
-- index on active sessions (revoked_at IS NULL) keeps the hot lookup fast.

CREATE TABLE identity_sessions (
    session_id   UUID        PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES identity_users (user_id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_sessions_user_id ON identity_sessions (user_id);
CREATE INDEX idx_sessions_active  ON identity_sessions (user_id) WHERE revoked_at IS NULL;
