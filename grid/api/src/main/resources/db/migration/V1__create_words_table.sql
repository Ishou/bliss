-- ADR-0013 §3 — Initial schema for the words corpus.
--
-- Owned by grid-api's Postgres database (CNPG cluster from ADR-0009). The
-- worker module (ADR-0013 §7) writes into this table; the API reads from
-- it via DatabaseWordRepository (ADR-0013 §8). Both apply Flyway on
-- startup against the same CNPG cluster.
--
-- Notes:
--   - `length` is a STORED generated column; the §2 query window
--     `length BETWEEN 2 AND 9` is enforced at SELECT time, not at insert.
--   - `word_id` is the wire-facing identifier per ADR-0003 §6 (UUID, not
--     integer). It defaults to v4 via pg_catalog.gen_random_uuid() (built-in
--     since PG 13; no extension required) until ADR-0013 §3's UUID v7
--     question is resolved in the worker PR.

CREATE TABLE words (
    id              BIGSERIAL PRIMARY KEY,
    word_id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    word            TEXT        NOT NULL,
    language        TEXT        NOT NULL DEFAULT 'fr',
    length          INT GENERATED ALWAYS AS (length(word)) STORED,
    difficulty      REAL,
    clue            TEXT,
    source          TEXT        NOT NULL,
    source_license  TEXT        NOT NULL,
    frequency       REAL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (word, language),
    UNIQUE (word_id)
);

CREATE INDEX words_lang_len ON words (language, length) INCLUDE (difficulty);
