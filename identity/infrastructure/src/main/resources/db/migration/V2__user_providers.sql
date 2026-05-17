-- identity_user_providers: links a player account to one or more OIDC providers (ADR-0044/ADR-0045).
-- provider CHECK ensures only 'google' or 'apple' are stored; wire values match Provider.toWire().
-- FK ON DELETE CASCADE: removing a user automatically cleans up their provider links.
-- UNIQUE (provider, subject): an IdP subject can only be linked to one Bliss account.

CREATE TABLE identity_user_providers (
    user_id       UUID        NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    provider      TEXT        NOT NULL CHECK (provider IN ('google', 'apple')),
    subject       TEXT        NOT NULL,
    email_at_link TEXT,
    linked_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, provider),
    UNIQUE (provider, subject)
);
