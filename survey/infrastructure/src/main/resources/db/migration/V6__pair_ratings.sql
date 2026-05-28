-- pair_ratings: strict preference table, every row is a usable DPO pair (ADR-0056 amendment 2026-05-28).
-- LEFT_WINS/RIGHT_WINS only at the DB layer; BOTH_GOOD/BOTH_BAD route to ratings; SKIP is not persisted.

CREATE TABLE pair_ratings (
    id             UUID PRIMARY KEY,
    left_item_id   UUID NOT NULL REFERENCES survey_items(item_id),
    right_item_id  UUID NOT NULL REFERENCES survey_items(item_id),
    user_id        UUID,
    verdict        TEXT NOT NULL CHECK (verdict IN ('left_wins', 'right_wins')),
    difficulte     SMALLINT NOT NULL CHECK (difficulte BETWEEN 1 AND 5),
    latency_ms     INTEGER,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (left_item_id <> right_item_id)
);

-- Authed user can't re-rate the same pair in either ordering.
CREATE UNIQUE INDEX pair_ratings_user_unique
    ON pair_ratings (user_id, LEAST(left_item_id, right_item_id), GREATEST(left_item_id, right_item_id))
    WHERE user_id IS NOT NULL;

CREATE INDEX pair_ratings_left_idx ON pair_ratings (left_item_id);
CREATE INDEX pair_ratings_right_idx ON pair_ratings (right_item_id);
