---
name: schemas
description: Edit, lint, or fix the OpenAPI / AsyncAPI specs in this repo (grid/api/openapi.yaml, game/api/asyncapi.yaml). Use when adding or editing endpoints / messages, when the spectral CI gate fails, when regen-and-diff fails after an OpenAPI change, when an auto-reviewer flags ADR-0003 conventions (UUID v7, ISO-8601, RFC 7807, explicit required/nullable, no cross-context $ref), or when a new schema PR is part of a Wave's schema-first phase. Encodes ADR-0003 (the cross-language API contract), ADR-0019 (AsyncAPI 2.6 exception), and ADR-0018 §5 (LobbyId base58 nanoid exception).
paths: ["**/openapi.yaml", "**/asyncapi.yaml"]
---

# Schema (OpenAPI + AsyncAPI) playbook

OpenAPI for REST, AsyncAPI for WebSocket / async event surfaces. The wire conventions are the same for both per ADR-0003 §6, so this skill covers them together.

## Anchor documents

- `docs/adr/0003-cross-language-api-contract.md` — the **single source of truth** for wire conventions. §1 says specs are hand-written, never generated from handler annotations. §6 codifies the conventions below.
- `docs/adr/0019-asyncapi-2.6-not-3.x.md` — AsyncAPI version exception, scoped to `game/api/asyncapi.yaml`.
- `docs/adr/0018-game-bounded-context-and-realtime.md` §5 — `LobbyId` is base58 nanoid, an explicit exception to ADR-0003 §6's UUID v7 default.
- `docs/adr/0001-parallel-agent-development-workflow.md` §3 — schema PRs are a hard barrier; they must merge before any implementation PR that consumes them.

## Wire conventions (ADR-0003 §6) — non-negotiable

These apply to BOTH OpenAPI and AsyncAPI unless a separate ADR carves an exception (see "Documented exceptions" below).

- **Identifiers**: UUID v7 strings. `format: uuid`. Never integers, never opaque strings (except where a separate ADR documents a deviation — currently only `LobbyId`).
- **Timestamps**: ISO-8601 with timezone offset. `format: date-time`. Example: `2026-04-24T15:30:00Z`.
- **Field naming**: camelCase. Period.
- **Errors**: RFC 7807 `application/problem+json`. Required fields: `type` (URI), `title`, `status`. Optional: `detail`, `instance`. Custom URIs at `https://bliss.example/errors/<slug>`.
- **String enums**: declare `enum: [...]` and pair with `x-enum-varnames: [...]` so codegen produces meaningful Kotlin/TypeScript identifiers (not `Value0`, `Value1`).
- **`required` lists every always-present field.** Nullable fields are still required if the wire always carries them — `null` is a value, absence is not.

### Absence ≠ null (the gotcha that has bitten three PRs in this rollout)

ADR-0003 §6 is explicit: "Absence and `null` are distinct." If a field is **always emitted on the wire** with `null` as a meaningful value:

```yaml
GameSession:
  type: object
  required: [puzzle, startedAt, completedAt]   # completedAt IS in required
  properties:
    completedAt:
      $ref: '#/components/schemas/Instant'
      nullable: true                            # null until the game ends
      description: Set once the lobby reaches COMPLETED.
```

If a field is genuinely optional (may be absent), it stays out of `required` and DOES NOT have `nullable: true`. The two states are distinct on the wire.

This bit PRs #122 (`LobbyStatePayload.game` and `GameSession.completedAt`) and #126's auto-review iteration. Front-load it.

## Documented exceptions (small, ADR-bound)

| Field / decision | Exception to | Documented in |
|---|---|---|
| `LobbyId` is 8-char base58 nanoid (no `0/O/I/l`) | ADR-0003 §6 UUID v7 default | ADR-0018 §5 (URL-friendliness in share links; trade-off: no time-sortability, no UUID infra reuse). |
| `game/api/asyncapi.yaml` uses AsyncAPI 2.6 | ADR-0003 §5 AsyncAPI 3.0 default | ADR-0019 (Spectral ruleset stability; 3.x channel-as-resource model complicates codegen). |
| `errorType` (not `type`) field on `ErrorPayload` | ADR-0003 §6 RFC 7807 default field name | Documented inline in the AsyncAPI: the top-level `type` field is the discriminator (`"type": "error"`), so the RFC 7807 type URI is exposed as `errorType` to avoid collision. |

Any new exception needs its own ADR or an amendment to an existing one. PR bodies do not count as decision records.

## No cross-context $ref (ADR-0001 §1)

The AsyncAPI cannot `$ref` into `grid/api/openapi.yaml`. Cross-bounded-context file dependencies are forbidden. Mirror the schema instead:

```yaml
# Mirrors grid/api/openapi.yaml#/components/schemas/Puzzle verbatim.
# Per ADR-0001 §1, cross-context $ref is forbidden — the game/ context
# owns its view of the wire shape. Update both files together when the
# puzzle wire format evolves.
GamePuzzle:
  type: object
  required: [id, title, language, width, height, cells, clues]
  properties: ...
```

The reviewer (and `claude-review` bot) will flag any cross-file `$ref` that crosses a bounded-context boundary.

## File layout

```
grid/api/openapi.yaml       # REST: GET /v1/puzzles/{puzzleId}
game/api/openapi.yaml       # REST: POST /v1/lobbies, GET /v1/lobbies/:id (Wave F)
game/api/asyncapi.yaml      # WebSocket: /v1/lobbies/:id/ws (current)
```

Each spec carries its own context's view of any shared shape — duplication over coupling.

## Spectral linting

Run before pushing:

```
# OpenAPI
npx -y @stoplight/spectral-cli@latest lint grid/api/openapi.yaml \
  --ruleset=@stoplight/spectral-rulesets/dist/oas

# AsyncAPI
npx -y @stoplight/spectral-cli@latest lint game/api/asyncapi.yaml \
  --ruleset=@asyncapi/spectral-ruleset
```

Goal: 0 errors, 0 warnings. The single info-level note `asyncapi-latest-version` (suggesting 3.1) is **acceptable** on `game/api/asyncapi.yaml` per ADR-0019; it should NOT appear on a 3.x spec or on the OpenAPI lint.

CI gate: the `spectral` GitHub Actions job runs the same rulesets. A green local run = a green CI lint.

## Frontend type regeneration

After every change to `grid/api/openapi.yaml` **or** `game/api/openapi.yaml`:

```
cd frontend
pnpm api:generate    # runs openapi-typescript against both specs
```

Commit the regenerated `frontend/src/infrastructure/api/{grid,game}/types.ts` in the **same PR** as the schema change. The CI check is `regen-and-diff` (workflow `openapi-typescript-drift.yml`): it reinstalls deps via `pnpm install --frozen-lockfile`, runs `pnpm api:generate`, then `git diff --exit-code` on both generated files. Either being stale fails the build.

**Pre-push self-check** — `pnpm api:check` is the one-liner that mirrors CI byte-for-byte:

```
cd frontend && pnpm api:check
```

If it passes locally, the CI check will pass too. Run it on every schema PR before pushing. The most common cause of a `regen-and-diff` red is "I edited the spec but didn't regenerate" — `pnpm api:check` catches that in five seconds.

Generated code is **excluded from ADR-0001 §4's line cap** — the regenerated `types.ts` doesn't count against the 400-line budget. That means: if your schema change produces a 600-line `types.ts` diff, you still pass §4 as long as the hand-written diff is under 400 lines.

AsyncAPI is **not** in this drift check. `game/api/asyncapi.yaml` does not have a comparable codegen step in this repo today; when Wave E adds the WebSocket client, codegen-from-AsyncAPI will be a separate concern (and a separate workflow).

## Versioning + path

- `info.version` follows the API surface, not the build. Bump when wire-incompatible changes ship.
- Endpoint prefixes are `/v1/...`. v2 paths land alongside v1; v1 is not deleted.
- Servers section: production at `wss://game.wordsparrow.io` for AsyncAPI, `https://api.wordsparrow.io/grid` for grid OpenAPI.

## Schema-first binds against `main`, not the wave plan

ADR-0001 §3 says schema PRs are a hard barrier and must merge **before** any implementation PR that depends on them. The rule binds against the merge tree on `main`, not against the dispatch wave plan. PRs #370 and #371 both shipped implementation that referenced fields/ADRs only present on a sibling, still-open PR — and burned review cycles re-flagging it.

Before opening a domain/application/frontend PR that consumes a schema:

1. `git fetch origin main` and verify the field/endpoint/ADR exists on `main` HEAD, not just on the wave's schema PR.
2. If the schema PR hasn't merged yet, either:
   - **Wait**, OR
   - **Cherry-pick** the schema file onto the dependent branch: `git checkout origin/<schema-branch> -- grid/api/openapi.yaml docs/adr/00NN-*.md`. Document the dependency in the PR body so the reviewer doesn't re-flag it.
3. Never claim "schema-first satisfied" in a PR body when the schema commit is on a sibling branch. The reviewer will catch it.

## Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `spectral` CI fails | New schema lint violation | Run spectral locally with the right ruleset; fix line by line. |
| `regen-and-diff` CI fails | OpenAPI changed but `frontend/.../types.ts` stale | `cd frontend && pnpm api:generate`; commit the diff. |
| Auto-review: "field absent from required but nullable" | Forgot to add the field to `required` | Add it. The field is always sent; `null` is the explicit blank value. |
| Auto-review: "ADR-XXXX missing from this branch" | Spec references an ADR that lives only on a sibling PR | Cherry-pick the ADR file: `git checkout origin/<sibling> -- docs/adr/XXXX-*.md`. Identical content in both branches conflict-merges cleanly. |
| Auto-review: "cross-context $ref" | `$ref: '../../grid/api/openapi.yaml#/...'` | Inline the schema with a "mirrors X.yaml" comment. ADR-0001 §1. |
| Auto-review: "ADR exception undocumented" | Used a non-default identifier shape / version / etc. without an ADR | File a small ADR (or amend an existing one) before re-pushing the schema. |
| ADR number collision (two PRs picked the next number simultaneously) | Concurrent dispatch + no ADR-number locking | One PR keeps the number, the other renumbers to next free. Update title, all in-doc refs, and references in companion specs. |
| Spectral 0 errors but auto-reviewer still flags | Some rules aren't in the public ruleset | Address the finding manually; lint catches a subset, conventions catch the rest. |
| Integration test passes but consumer sees `required` field missing on the wire | Server-side `Json {}` builder lacks `encodeDefaults = true`; defaulted collection serialized as omitted | This is a backend-side fix even though the symptom is a schema-contract violation. See the dedicated section in `/jvm-backend`. PR #401. |
| Auto-review: "schema-first violated — field/ADR not on main" | Implementation references a schema commit that lives only on the sibling schema PR | Either wait for the schema PR to merge, or cherry-pick its file onto your branch. The §3 barrier binds against `main`. PRs #370, #371. |

## Don'ts

- **Don't** add a non-default identifier format (anything other than UUID v7) without filing an ADR.
- **Don't** cross-reference between specs in different bounded contexts via `$ref`.
- **Don't** edit generated TypeScript types directly.
- **Don't** use `additionalProperties: true` to paper over a missing schema. Define the shape.
- **Don't** mix `required` and `nullable` ambiguously: re-read the ADR-0003 §6 distinction every time.
- **Don't** invent new error URIs without slotting them under `https://bliss.example/errors/<slug>`.
- **Don't** bump AsyncAPI to 3.x without a superseding ADR for ADR-0019.
- **Don't** add a `$ref` chain longer than two hops; if you're tempted, the schema needs flattening.
