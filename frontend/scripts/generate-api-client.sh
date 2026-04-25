#!/usr/bin/env sh
# Regenerate the Grid API TypeScript types from the OpenAPI spec.
#
# The committed `src/infrastructure/api/grid/types.ts` file is the output of
# this script; the CI regen-and-diff gate (per ADR-0003 §8.2,
# `.github/workflows/openapi-typescript-drift.yml`) fails if a contributor
# edits the spec without re-running it. Run this after every change to
# `grid/api/openapi.yaml` and commit the diff alongside the spec change.
#
# Equivalent to `pnpm --filter frontend api:generate`. Provided as a shell
# script for contributors who prefer to run a single command from the repo
# root or from `frontend/`.

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$FRONTEND_DIR"
exec pnpm api:generate
