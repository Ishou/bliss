-- Campaign-bounded rating sessions (ADR-0059); nullable campaign_id FKs on ratings/pair_ratings.

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
