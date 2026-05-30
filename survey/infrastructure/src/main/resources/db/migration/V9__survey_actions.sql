-- survey_actions: one row per reversible rating submit, carrying the reversal recipe (ADR-0059).
-- proposed_item_id unwinds the proposed_by link when a text-correctif reused an existing item.

CREATE TABLE survey_actions (
    action_id           UUID PRIMARY KEY,
    undo_token_hash     BYTEA NOT NULL UNIQUE,
    user_id             UUID,
    kind                TEXT NOT NULL CHECK (kind IN ('binary', 'pair', 'correctif')),
    campaign_id         UUID NOT NULL REFERENCES campaigns(campaign_id),
    created_at          TIMESTAMPTZ NOT NULL,
    undone_at           TIMESTAMPTZ,
    created_rating_ids  UUID[] NOT NULL DEFAULT '{}',
    created_pair_id     UUID,
    created_item_id     UUID,
    proposed_item_id    UUID,
    patched_item_id     UUID,
    prior_pos           TEXT,
    prior_last_rated_at TIMESTAMPTZ
);

CREATE INDEX survey_actions_campaign_idx ON survey_actions (campaign_id);
