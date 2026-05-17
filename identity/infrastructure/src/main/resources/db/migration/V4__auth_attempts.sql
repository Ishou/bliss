-- Identity context: in-flight OIDC handshakes (ADR-0044 §3).
--
-- state is the CSRF token (43 url-safe base64 chars, no padding).
-- pkce_verifier is the plain-text PKCE code verifier (43 chars for 32-byte
-- random input, RFC 7636 §4.1). return_to is the post-login redirect path.
-- link_to_user_id is non-null only in linking mode (adding an IdP to an
-- existing signed-in account). expires_at drives TTL cleanup; rows older
-- than expires_at are candidates for a background sweep (not implemented yet).

CREATE TABLE identity_auth_attempts (
    attempt_id       UUID        PRIMARY KEY,
    state            TEXT        NOT NULL UNIQUE,
    pkce_verifier    TEXT        NOT NULL,
    provider         TEXT        NOT NULL,
    return_to        TEXT        NOT NULL,
    link_to_user_id  UUID        REFERENCES identity_users (user_id) ON DELETE SET NULL,
    expires_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auth_attempts_state ON identity_auth_attempts (state);
