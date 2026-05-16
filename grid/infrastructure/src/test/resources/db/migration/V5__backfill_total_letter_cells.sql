-- Backfill total_letter_cells IS NULL rows from placement geometry (V4 shipped the column without a backfill).

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
