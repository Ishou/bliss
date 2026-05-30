-- Campaign-bounded rating sessions (ADR-0059). Partial unique invariant holds at most
-- one open campaign at a time; ratings + pair_ratings gain nullable campaign_id FKs.
-- Application stamps campaign_id from Generators.timeBasedEpochGenerator(); the
-- maintainer's psql verbs may rely on the pg_uuidv7 extension (installed out-of-band
-- in prod) or generate UUIDs externally.

CREATE TABLE campaigns (
    campaign_id  UUID         PRIMARY KEY,
    batch_label  TEXT         NOT NULL,
    opened_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX campaigns_one_open
    ON campaigns ((1)) WHERE closed_at IS NULL;

CREATE INDEX campaigns_opened_at_idx
    ON campaigns (opened_at DESC);

ALTER TABLE ratings
    ADD COLUMN campaign_id UUID REFERENCES campaigns(campaign_id);
CREATE INDEX ratings_campaign_idx ON ratings (campaign_id);

ALTER TABLE pair_ratings
    ADD COLUMN campaign_id UUID REFERENCES campaigns(campaign_id);
CREATE INDEX pair_ratings_campaign_idx ON pair_ratings (campaign_id);
