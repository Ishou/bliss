-- Adds the denormalised letter-cell count to puzzles so the
-- listDailyPuzzles endpoint can return archive rows without parsing the
-- full payload JSONB per row. Nullable to keep the migration reversible
-- and because a SQL-only backfill is not viable here: payload stores
-- the placements list (see PuzzlePayload.kt), not the materialised
-- cells/letters map, so totalLetterCells is only computable by walking
-- the domain Grid.fromPlacements pipeline in JVM code.
--
-- The application layer treats NULL defensively: findSummariesByIds
-- filters rows whose total_letter_cells IS NULL so pre-backfill leftovers
-- never reach the wire. New rows inserted via PostgresPuzzleRepository
-- always populate the column (derived from StoredPuzzle.grid). Existing
-- pre-V4 rows will appear in the archive only after they are rewritten —
-- acceptable because the archive endpoint is unreleased and any pre-V4
-- daily row is from a sandbox / pre-alpha window.

ALTER TABLE puzzles
    ADD COLUMN total_letter_cells INTEGER;
