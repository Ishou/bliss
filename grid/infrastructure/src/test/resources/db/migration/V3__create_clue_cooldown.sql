-- Per-bucket monotonic generation counter. One row per bucket — either a
-- sessionId from `X-Session-Id` or the reserved DAILY_SCOPE_ID sentinel
-- per ADR-0031. The column name `session_id` matches `puzzle_hint_usage`'s
-- shape; the daily bucket is a sentinel UUID by convention.
CREATE TABLE clue_cooldown_session (
    session_id      UUID        PRIMARY KEY,
    generation_seq  BIGINT      NOT NULL DEFAULT 0
        CHECK (generation_seq >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Active cooldown rows. PK is (session, word, clue) — clue text is the
-- only stable identity available (Word.clues is a list whose indices are
-- fragile across CSV regenerations). Rows whose cooldown_until_seq is
-- <= the bucket's current generation_seq are inert and may be pruned by a
-- background job (out of scope for V3).
CREATE TABLE clue_cooldown (
    session_id          UUID        NOT NULL
        REFERENCES clue_cooldown_session(session_id) ON DELETE CASCADE,
    word_text           TEXT        NOT NULL,
    clue_text           TEXT        NOT NULL,
    cooldown_until_seq  BIGINT      NOT NULL,
    last_used_seq       BIGINT      NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, word_text, clue_text)
);

CREATE INDEX idx_clue_cooldown_session_until
    ON clue_cooldown (session_id, cooldown_until_seq);
