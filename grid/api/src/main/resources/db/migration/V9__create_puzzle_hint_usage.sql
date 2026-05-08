-- Per-(puzzle, player) hint counter. Decoupled from the puzzles row so a
-- shared puzzleId URL gives every caller their own bucket — a stranger
-- opening the same link can't burn hints out from under the original player.
--
-- session_id is the X-Session-Id header value (UUID v7, mirrors game/api's
-- SessionId convention). The wire contract intentionally leaves the
-- identification mechanism flexible (per the OpenAPI description); for v1
-- the API accepts the header verbatim and rejects malformed values with 400.
--
-- Atomic spend is one statement away thanks to the FK + CHECK pair:
--   INSERT INTO puzzle_hint_usage (puzzle_id, session_id, hints_used)
--     SELECT $1, $2, 1 FROM puzzles WHERE puzzle_id = $1
--     ON CONFLICT (puzzle_id, session_id)
--     DO UPDATE SET hints_used = puzzle_hint_usage.hints_used + 1,
--                   updated_at = now()
--     WHERE puzzle_hint_usage.hints_used <
--           (SELECT hints_allowed FROM puzzles WHERE puzzle_id = $1)
--     RETURNING hints_used;
-- Empty result == cap reached → 429.

CREATE TABLE puzzle_hint_usage (
    puzzle_id    UUID        NOT NULL REFERENCES puzzles(puzzle_id) ON DELETE CASCADE,
    session_id   UUID        NOT NULL,
    hints_used   INT         NOT NULL DEFAULT 0 CHECK (hints_used >= 0),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (puzzle_id, session_id)
);
