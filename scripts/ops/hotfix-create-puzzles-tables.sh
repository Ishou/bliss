#!/usr/bin/env bash
# One-shot recovery for the prod CNPG database after PR #222 (puzzle
# persistence) failed to create the `puzzles` + `puzzle_hint_usage` tables
# on rollout. PR #224 is the proper Flyway-driven fix; this script unblocks
# prod while you wait on the next deploy.
#
# Why this is safe to combine with PR #224:
#   - Both CREATE TABLEs use IF NOT EXISTS, matching the V8/V9 migration
#     SQL on disk after PR #224.
#   - We do NOT touch flyway_schema_history. On the next deploy, Flyway
#     runs V8/V9 normally; the IF NOT EXISTS guards short-circuit the
#     CREATEs and the rows get recorded with real, Flyway-computed
#     checksums. No fabricated audit history.
#
# Requirements: kubectl pointed at the prod cluster, with `exec` permission
# on the wordsparrow namespace.
#
# Verify after running:
#   curl -s https://api.wordsparrow.io/v1/puzzles/$(uuidgen) | jq .
#   # expect a 200 with a puzzle body, not a 500 problem-detail.

set -euo pipefail

NAMESPACE="${NAMESPACE:-wordsparrow}"
CLUSTER="${CLUSTER:-wordsparrow-api-pg}"

primary=$(kubectl -n "$NAMESPACE" get pods \
  -l "cnpg.io/cluster=${CLUSTER},role=primary" \
  -o jsonpath='{.items[0].metadata.name}')

if [[ -z "$primary" ]]; then
  echo "error: no primary pod found for CNPG cluster '$CLUSTER' in namespace '$NAMESPACE'" >&2
  exit 1
fi

echo "primary pod: $primary"

kubectl -n "$NAMESPACE" exec -i "$primary" -c postgres -- \
  psql -U wordsparrow -d wordsparrow -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

CREATE TABLE IF NOT EXISTS puzzles (
    puzzle_id      UUID PRIMARY KEY,
    width          INT         NOT NULL CHECK (width  BETWEEN 1 AND 50),
    height         INT         NOT NULL CHECK (height BETWEEN 1 AND 50),
    language       TEXT        NOT NULL DEFAULT 'fr',
    title          TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    hints_allowed  INT         NOT NULL DEFAULT 3 CHECK (hints_allowed >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS puzzle_hint_usage (
    puzzle_id    UUID        NOT NULL REFERENCES puzzles(puzzle_id) ON DELETE CASCADE,
    session_id   UUID        NOT NULL,
    hints_used   INT         NOT NULL DEFAULT 0 CHECK (hints_used >= 0),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (puzzle_id, session_id)
);

COMMIT;

\echo
\echo '--- tables in public schema ---'
\dt public.*
SQL

echo
echo "done. running pods will pick up the new tables on the next request — no restart needed."
echo "verify: curl -s https://api.wordsparrow.io/v1/puzzles/\$(uuidgen) | jq ."
