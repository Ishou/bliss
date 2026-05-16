-- Backfill total_letter_cells for puzzles inserted before the write-path
-- update landed in V4 / PR #450. V4 added the column as nullable with no
-- backfill because, at the time, deriving the count in SQL was deemed
-- non-viable. In production the result is that historical rows remain
-- NULL forever, and findSummariesByIds filters NULLs defensively, which
-- collapses /v1/puzzles/daily/list (and the /grilles archive) to empty.
--
-- The payload JSONB shape is payload.placements[] — see
-- grid/infrastructure/.../persistence/PuzzlePayload.kt. We can derive
-- the cell count purely from the placement geometry: each placement
-- covers char_length(wordText) cells starting at one neighbour of
-- (cluePositionRow, cluePositionColumn) and stepping in the placement's
-- direction. The total letter-cell count for a puzzle is the number of
-- distinct (row, col) tuples covered by any placement (intersections
-- collapse, matching Grid.fromPlacements which de-duplicates positions
-- via the letters map).
--
-- Direction → cell-offset formula verified against
-- grid/domain/.../model/Direction.kt (startOffset + step * i) and
-- WordPlacement.letterPositions():
--   RIGHT       (startOffset (0,1), step (0,1)) → (r,     c+1+i)
--   DOWN        (startOffset (1,0), step (1,0)) → (r+1+i, c)
--   DOWN_RIGHT  (startOffset (1,0), step (0,1)) → (r+1,   c+i)
--   RIGHT_DOWN  (startOffset (0,1), step (1,0)) → (r+i,   c+1)
-- where i ∈ [0, char_length(wordText)).
--
-- wordText is constrained to A–Z in the domain (see Word.kt invariants),
-- so char_length is one cell per char with no normalisation surprises.
--
-- Idempotent: only touches rows where total_letter_cells IS NULL.

WITH placement_cells AS (
    SELECT
        p.puzzle_id,
        CAST(pl ->> 'cluePositionRow' AS INTEGER) +
            CASE pl ->> 'direction'
                WHEN 'DOWN'       THEN 1 + i
                WHEN 'DOWN_RIGHT' THEN 1
                WHEN 'RIGHT_DOWN' THEN i
                ELSE 0
            END AS cell_row,
        CAST(pl ->> 'cluePositionColumn' AS INTEGER) +
            CASE pl ->> 'direction'
                WHEN 'RIGHT'      THEN 1 + i
                WHEN 'DOWN_RIGHT' THEN i
                WHEN 'RIGHT_DOWN' THEN 1
                ELSE 0
            END AS cell_col
    FROM puzzles p
    CROSS JOIN LATERAL jsonb_array_elements(p.payload -> 'placements') AS pl
    CROSS JOIN LATERAL generate_series(0, char_length(pl ->> 'wordText') - 1) AS i
    WHERE p.total_letter_cells IS NULL
),
counts AS (
    SELECT puzzle_id, COUNT(DISTINCT (cell_row, cell_col)) AS letter_count
    FROM placement_cells
    GROUP BY puzzle_id
)
UPDATE puzzles
SET total_letter_cells = counts.letter_count
FROM counts
WHERE puzzles.puzzle_id = counts.puzzle_id;
