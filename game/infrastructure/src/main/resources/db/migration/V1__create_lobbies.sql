-- V1__create_lobbies.sql
-- Wave B - PR #3 of the multi-day multiplayer persistence rollout.
-- See ADR-0039 (docs/adr/0039-multiplayer-game-persistence.md) for the
-- schema design rationale, the GC retention matrix, and the RGPD erasure
-- cascade that motivates `lobby_cell_entries.written_by_session_id`
-- being NULLable.
--
-- Three tables, all keyed by `lobby_id`:
--   - lobbies              the aggregate root.
--   - lobby_players        membership rows (composite PK).
--   - lobby_cell_entries   last-write-wins letters typed into the grid.
--
-- ON DELETE CASCADE on both child tables means deleting a lobbies row
-- atomically removes its players + entries (the owner-erasure path in
-- ADR-0039 rule #1). Last-write-wins remains application-enforced via
-- the per-lobby `SELECT ... FOR UPDATE` transaction in the adapter
-- (lands in PR #5); the PK on (lobby_id, row, col) just guarantees the
-- upsert target.
--
-- `lobbies.id` is TEXT (8-char base58 nanoid per ADR-0020), not UUID.
-- `owner_session_id` and `written_by_session_id` are UUID — they hold
-- the anonymous session identifier (UUID v7 per ADR-0003 SS6).
-- `game_payload` is JSONB and opaque to the database: it stores the
-- GameSession projection (puzzle + startedAt + completedAt +
-- lockedPositions); only entries are normalised, because resume
-- hydration is a plain join on lobby_cell_entries.

CREATE TABLE lobbies (
    id                TEXT        PRIMARY KEY,
    code              TEXT        NOT NULL UNIQUE,
    owner_session_id  UUID        NOT NULL,
    state             TEXT        NOT NULL CHECK (state IN ('WAITING', 'IN_PROGRESS', 'COMPLETED')),
    grid_width        INT         NOT NULL,
    grid_height       INT         NOT NULL,
    title             TEXT        NULL,
    game_payload      JSONB       NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at  TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ NULL
);

-- Powers LobbyGarbageCollector's `findIdleWaiting(cutoff)` and the
-- forthcoming `findIdleCompleted(cutoff)` (ADR-0039 GC matrix:
-- IN_PROGRESS never evicted, WAITING 24h, COMPLETED 7d).
CREATE INDEX lobbies_state_last_activity_idx
    ON lobbies (state, last_activity_at);

CREATE TABLE lobby_players (
    lobby_id    TEXT        NOT NULL REFERENCES lobbies(id) ON DELETE CASCADE,
    session_id  UUID        NOT NULL,
    pseudonym   TEXT        NOT NULL,
    joined_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (lobby_id, session_id)
);

-- Powers the `sessionId -> lobbyIds` lookup behind
-- `GET /v1/sessions/{sessionId}/lobbies` (PR #6/#7).
CREATE INDEX lobby_players_session_idx
    ON lobby_players (session_id);

CREATE TABLE lobby_cell_entries (
    lobby_id               TEXT        NOT NULL REFERENCES lobbies(id) ON DELETE CASCADE,
    row                    INT         NOT NULL,
    col                    INT         NOT NULL,
    letter                 CHAR(1)     NOT NULL,
    written_by_session_id  UUID        NULL,
    written_at             TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (lobby_id, row, col)
);
