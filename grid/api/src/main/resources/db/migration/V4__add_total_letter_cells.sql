-- nullable: SQL backfill not viable (payload stores placements, not materialised cells); new rows always populated via app layer.

ALTER TABLE puzzles
    ADD COLUMN total_letter_cells INTEGER;
