-- proposed_by: authorship link for rater-proposed corpus rows. opted_out
-- toggles whether the contribution is deleted from the corpus on
-- user.deleted (spec §10.2). Fully deleted on user.deleted regardless of
-- opted_out (the row itself was the only place authorship was recorded).

CREATE TABLE proposed_by (
    proposed_item_id UUID PRIMARY KEY REFERENCES survey_items(item_id),
    user_id          UUID NOT NULL,
    opted_out        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX proposed_by_user_idx ON proposed_by (user_id);
