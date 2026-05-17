-- identity_auth_attempts: tracks in-flight OIDC authorisation requests (ADR-0044/ADR-0045).
-- attempt_id is a surrogate UUID PK; state is the opaque CSRF token and must be UNIQUE.
-- provider CHECK ensures only 'google' or 'apple' are stored; wire values match Provider.toWire().
-- link_to_user_id is nullable: non-null when initiated from a signed-in session (linking mode).
-- FK ON DELETE CASCADE: removing a user also removes any in-flight linking attempt for that user.
-- Index on expires_at supports future TTL-based cleanup queries.

CREATE TABLE identity_auth_attempts (
    attempt_id       UUID        PRIMARY KEY,
    state            TEXT        NOT NULL UNIQUE,
    pkce_verifier    TEXT        NOT NULL,
    provider         TEXT        NOT NULL CHECK (provider IN ('google', 'apple')),
    return_to        TEXT        NOT NULL,
    link_to_user_id  UUID        REFERENCES identity_users(user_id) ON DELETE CASCADE,
    expires_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_identity_auth_attempts_expires_at ON identity_auth_attempts (expires_at);
