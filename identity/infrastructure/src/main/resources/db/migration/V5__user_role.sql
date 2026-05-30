-- authz primitive (ADR-0060); DEFAULT 'player' makes this column additive (no backfill needed).
ALTER TABLE identity_users
    ADD COLUMN role TEXT NOT NULL DEFAULT 'player'
        CHECK (role IN ('player', 'maintainer'));
