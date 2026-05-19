-- Re-key puzzle_hint_usage from session_id to user_id. Hard cutover: no production rows to preserve.
TRUNCATE TABLE puzzle_hint_usage;

ALTER TABLE puzzle_hint_usage DROP CONSTRAINT puzzle_hint_usage_pkey;
ALTER TABLE puzzle_hint_usage DROP COLUMN session_id;
ALTER TABLE puzzle_hint_usage ADD COLUMN user_id UUID NOT NULL;
ALTER TABLE puzzle_hint_usage ADD PRIMARY KEY (puzzle_id, user_id);
CREATE INDEX puzzle_hint_usage_user_id_idx ON puzzle_hint_usage (user_id);
