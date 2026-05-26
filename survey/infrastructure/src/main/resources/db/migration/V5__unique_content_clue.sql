-- Reject duplicate clues by content. Same (mot, definition) within the
-- unretired set is the same thing to a rater; the V1 "re-rate across
-- model iterations" intent is dropped — RAFT-round candidates ship with
-- net-new definitions so the cross-batch re-rating window is unused, and
-- a manual re-import collapses to one logical row.

CREATE UNIQUE INDEX survey_items_content_uniq
    ON survey_items (mot, definition)
    WHERE retired_at IS NULL;
