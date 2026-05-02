# ADR-0022: Application-Layer Deviations from ADR-0018

## Status

Accepted

## Context

ADR-0018 §1 listed the `game:application` ports as including `LobbyEventBroadcaster` and
the use cases as including `EvaluateSolved`. PR #127 implemented the application layer with
two intentional deviations from those specifications. This ADR records and justifies both
deviations so the accepted-ADR corpus remains consistent with the codebase.

## Decision

### 1. `LobbyEventBroadcaster` replaced by `List<LobbyEvent>` in `UseCaseResult`

ADR-0018 §1 named `LobbyEventBroadcaster` as a port through which use cases would push
events. The implementation instead embeds events as `List<LobbyEvent>` in the
`UseCaseResult<T>` return type.

**Rationale:** A push-to-broadcaster port would couple use cases to a transport concern —
the use case would need to inject and call an infrastructure boundary. Returning events as
values keeps the application layer pure: use cases compute state and declare events; the
API layer (Wave F) receives the result and fans out to WebSocket subscribers. The seam ADR-0018
§"Future work" cited (`LobbyRepository` and `LobbyEventBroadcaster` as the upgrade points
for multi-replica) is preserved: the API layer that fans out events is the natural adapter
boundary, and a future Redis-backed broadcaster slots in there without touching use cases.

### 2. `EvaluateSolved` inlined into `UpdateCellUseCase`

ADR-0018 §1 listed `EvaluateSolved` as a standalone use case. The implementation inlines
solved detection into `UpdateCellUseCase` via `GameSession.isSolved()` (already on the
domain object, PR #126).

**Rationale:** Splitting into two use cases would require a second `mutate` round-trip and
a re-check of the solved condition, introducing a TOCTOU window between writing the cell
and evaluating solved status. The per-lobby lock makes the write + solve-check an atomic
unit: no intermediate state is observable. `GameSession.isSolved()` encapsulates the domain
logic; a thin `EvaluateSolved` wrapper would add indirection with no independent testable
behaviour.

### 3. Wave F PR #10 exceeds ADR-0001 §4 400-line cap

ADR-0001 §4 sets a hard cap of 400 non-blank, non-generated lines per PR. Wave F PR #10
(`feat/game-api-websocket-endpoint`) ships approximately 731 main + 436 test non-blank lines.

**Rationale:** The natural split (SessionManager + DTOs | route + route tests) was evaluated:
each half still exceeds the cap (~610 + ~495 non-blank LOC) and would require duplicating the
Ktor `testApplication` harness or leaving the first half untested against real wire shapes.
Tight coupling between the route, mapper, DTOs, and their integration tests makes further
splitting impractical without incurring more review burden than the single large PR. The Wave F
dispatch brief explicitly anticipated and accepted this outcome.

### 4. `SystemClock` deferred to `game:api` instead of `game:infrastructure`

ADR-0018 §1 places production port adapters in `game:infrastructure`. `SystemClock` (a `Clock`
port adapter calling `Instant.now()`) lives in `game:api` in Wave F.

**Rationale:** The Wave F brief explicitly excluded `game:infrastructure` changes; the
implementation is a single-line adapter with no infrastructure dependencies. Promoting it to
`game:infrastructure` would require a separate PR touching that module, which is disproportionate
for a one-liner. Promote to `game:infrastructure` when a second consumer appears.

### 5. Wave H PR #146 exceeds ADR-0001 §4 400-line cap

ADR-0001 §4 sets a hard cap of 400 non-blank, non-generated lines per PR. Wave H PR #146
(`feat/frontend-game-lobby-integration`) ships approximately 306 implementation + 249 test
non-blank lines ≈ 555 non-blank total.

**Rationale:** The natural split (route wiring | Wave H integration tests) was evaluated:
each half is under the cap (~306 + ~249 non-blank LOC). However, the 8 new
`describe('Lobby route Wave H integration', …)` test cases are the primary verification for
the 8 new behaviors introduced in `lobby.$lobbyId.tsx` (rename, set-grid-config, start-game,
clipboard copy, game-started transition, gameSolved modal, modal dismiss, cell-update
forwarding). Splitting would land the implementation on `main` untested for the window
between merging PR A and PR B, temporarily violating the TDD principle in `MANIFESTO.md`.
Unlike ADR-0022 §3 (where neither Wave F half fit under the cap), here the split is
mechanically viable; the justification is co-shipping of implementation and its covering tests.

## Consequences

- `LobbyEventBroadcaster` does not exist as a port. Wave D (infrastructure) does not
  implement it. Wave F receives events from use-case return values and owns fan-out.
- End-of-game stats (ADR-0018 §"Consequences / Easier") are surfaced via
  `LobbyEvent.GameSolved` in the use-case result list, not through a separate use case.
- The multi-replica upgrade path (ADR-0018 §"Future work") is unaffected: `LobbyRepository`
  remains the primary seam, and the Wave F broadcast adapter is the secondary seam.
