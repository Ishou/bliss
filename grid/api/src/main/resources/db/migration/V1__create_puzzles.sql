-- Persistent storage for generated puzzles (ADR-0013 §8 reversed for runtime
-- state — the committed CSV remains the corpus source of truth, but the
-- canonical solution for a given puzzleId now lives here so that GET is
-- idempotent and the validate / hints endpoints have a stable referent).
--
-- payload is JSONB rather than a normalized cells/clues table: the domain
-- already round-trips through Grid.fromPlacements(width, height, placements)
-- so storing the placements list is the minimum sufficient state. JSONB keeps
-- a single-row read path for the hot lookup; querying inside the document is
-- not required by either endpoint.
--
-- hints_allowed is the per-puzzle cap (default 3). The puzzle row carries no
-- consumed counter; per-(puzzle, player) usage lives in puzzle_hint_usage so
-- one shared puzzleId URL doesn't burn through hints across users.

CREATE TABLE puzzles (
    puzzle_id      UUID PRIMARY KEY,
    width          INT         NOT NULL CHECK (width  BETWEEN 1 AND 50),
    height         INT         NOT NULL CHECK (height BETWEEN 1 AND 50),
    language       TEXT        NOT NULL DEFAULT 'fr',
    title          TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    hints_allowed  INT         NOT NULL DEFAULT 3 CHECK (hints_allowed >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
