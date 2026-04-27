-- ADR-0013 §3 — initial schema for the words corpus.
-- Mirror of grid/api/src/main/resources/db/migration/V1__create_words_table.sql;
-- consolidate when a third consumer appears. Each module ships its own copy
-- because Flyway loads from `classpath:db/migration` per module and CLAUDE.md
-- requires modules to build independently. Flyway treats identical
-- version+checksum as no-op (ADR-0013 §6).

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
