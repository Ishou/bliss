# Analytics Event Taxonomy

> Single source of truth for the analytics events Bliss emits. The
> frontend tracker and the backend `MatomoAnalyticsAdapter` both follow
> this list. Adding, removing, or renaming an event requires a PR that
> updates this file.

## Naming convention

Events are namespaced and versioned:

```
<bounded-context>:<event>:v<n>
```

- `<bounded-context>` is the emitter's context (`game`, `grid`,
  `frontend` for client-only events).
- `<event>` is `snake_case`, describes the user-meaningful action.
- `v<n>` starts at `v1`. Bump to `v2` whenever the event's properties
  change shape (field renamed, removed, or semantics shifted). The two
  versions then coexist for a deprecation window before `v1` is removed.

Examples:

- `game:lobby_created:v1`
- `grid:hint_used:v1`
- `frontend:grid_abandoned:v1`

## Property conventions

- Property keys are `camelCase`, matching ADR-0003 §6.
- **No `sessionId`, IP, pseudonym, or any direct identifier** in
  properties. Visitor identity is carried by Matomo's `_id` field
  (daily-rotated salted hash, see ADR-0025 §3).
- Numeric properties use SI-aligned units (`durationMs`, `gridSize`).
- Optional properties may be absent; do **not** ship `null` placeholders.

## Initial event set

Phase 4 wires these events. The list is intentionally small; future
events go through the same naming and review process.

### `game:` (multiplayer lifecycle, server-emitted)

| Event | When | Properties |
|---|---|---|
| `game:lobby_created:v1` | `CreateLobby` use case succeeds | `gridSize` (e.g. `"11x11"`) |
| `game:lobby_joined:v1` | `JoinLobby` use case succeeds | `playerCount` (after join) |
| `game:player_renamed:v1` | `RenameSelf` use case succeeds | (no properties) |
| `game:started:v1` | `StartGame` use case succeeds | `gridSize`, `playerCount` |
| `game:solved:v1` | `EvaluateSolved` flips state to `COMPLETED` | `gridSize`, `playerCount`, `durationMs` |
| `game:lobby_left:v1` | `LeaveLobby` use case succeeds | (no properties) |

### `grid:` (puzzle lifecycle, server-emitted)

| Event | When | Properties |
|---|---|---|
| `grid:puzzle_generated:v1` | A puzzle is generated and persisted | `gridSize`, `language`, `wordsCount` |
| `grid:hint_used:v1` | Hint endpoint returns a letter | `gridSize`, `hintsUsedSoFar` |

### `frontend:` (client-only, can't be observed server-side)

| Event | When | Properties |
|---|---|---|
| `frontend:grid_started:v1` | Player enters the grid view, focuses a cell | `gridSize`, `mode` (`"solo"` \| `"multi"`) |
| `frontend:grid_abandoned:v1` | Player navigates away from a grid before solving | `gridSize`, `mode`, `cellsFilled` |

## Page views

Tracked automatically by the frontend tracker via TanStack Router on
every route change. URL is the canonical event title; query parameters
are stripped client-side before the tracker call to avoid leaking
shareable lobby IDs as analytics identifiers.

## Versioning workflow

When an event's shape needs to change:

1. Add the new event under a bumped version (`v2`) in this file.
2. Update emitters to dual-write `v1` and `v2` for one release cycle.
3. After the cycle, drop `v1` from emitters and from this table.
4. Existing Matomo reports are updated to reference the new name.

This mirrors the spec-first / contract-first discipline of ADR-0003 for
HTTP and AsyncAPI events.

## Out-of-band properties

Matomo automatically attaches the rotated visitor hash, page URL, user
agent (anonymized), referrer, and country (no city). These are **not**
declared in this file because they are platform-level metadata, not
event-level properties.

## What this file is **not**

- Not a list of metrics or dashboards. Those live in Matomo itself.
- Not a list of OpenTelemetry spans. Distributed tracing is a distinct
  workstream tracked under ADR-0007.
- Not a place to spec UI features. Use [`IDEAS.md`](../../IDEAS.md) and
  [`docs/superpowers/specs/`](../superpowers/specs/) for that.
