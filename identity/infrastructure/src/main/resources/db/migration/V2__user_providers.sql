-- Identity context: per-user IdP links (ADR-0044 §4).
--
-- (user_id, provider) is the natural PK: one account per IdP per user.
-- subject is the opaque `sub` claim from the OIDC ID token — preserved
-- verbatim. email_at_link is nullable; only stored when the user opted in
-- to email retention (ADR-0045 §4).
-- A secondary unique index on (provider, subject) lets BeginOidcLoginUseCase
-- look up an existing link without scanning by user_id.

CREATE TABLE identity_user_providers (
    user_id       UUID        NOT NULL REFERENCES identity_users (user_id) ON DELETE CASCADE,
    provider      TEXT        NOT NULL,
    subject       TEXT        NOT NULL,
    email_at_link TEXT,
    linked_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, provider)
);

CREATE UNIQUE INDEX idx_user_providers_provider_subject
    ON identity_user_providers (provider, subject);
