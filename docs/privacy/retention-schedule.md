# Data Retention Schedule

> Authoritative list of how long WordSparrow keeps each piece of data and how it
> is enforced. Any new processing activity introduced by a feature must add
> a row here as a precondition to merge.

## Schedule

| Data | Storage | Retention | Enforcement |
|---|---|---|---|
| `sessionId` (UUID v7) | Browser `localStorage` | Until the user clears browser storage or clicks "Erase my data" | User-controlled; see ADR-0021 |
| Pseudonym | Browser `localStorage` | Same as `sessionId` | User-controlled |
| `puzzle_hint_usage` rows | Postgres (CNPG) | **90 days from `updated_at`** | k8s CronJob (Phase 7), nightly `DELETE WHERE updated_at < now() - interval '90 days'` |
| Lobby row (`lobbies`) | Postgres (CNPG, `game-api` schema) | WAITING: 30 min idle; IN_PROGRESS: never evicted; COMPLETED: 7 days | k8s CronJob (ADR-0039 §c), nightly sweep |
| Lobby membership row (`lobby_players`) | Postgres | Lifetime of the parent lobby (`ON DELETE CASCADE`) | Inherits lobby retention |
| Cell entry row (`lobby_cell_entries`) | Postgres | Lifetime of the parent lobby; `written_by_session_id` is NULLed on RGPD erasure (letter retained for puzzle coherence, attribution dropped) | Inherits lobby retention; ADR-0039 three-rule cascade for erasure |
| Matomo visit + event data | MariaDB (k3s, dedicated to Matomo) | **13 months** | Matomo admin UI: Privacy → Anonymize previous logs |
| Matomo aggregated reports | MariaDB | Indefinite (already aggregated, non-identifying) | n/a |
| Application logs (Ktor `CallLogging`) | stdout, scraped to log store TBD | Pending observability ADR-0007 | Out of scope here |
| Cloudflare access logs | Cloudflare-side | Per Cloudflare's own retention policy | Outside WordSparrow control |

## Special cases

### Erasure on user request

When a user clicks "Erase my data", the following deletions happen in
parallel and **all must succeed** for the UI to claim erasure (a partial
failure surfaces "Veuillez réessayer"):

1. Frontend: `localStorage.clear()` for the WordSparrow keys (`bliss.session.id`,
   `bliss.session.pseudonym`).
2. Backend: `DELETE /v1/sessions/{sessionId}` on `grid/api` removes all
   `puzzle_hint_usage` rows for that session.
3. Backend: `DELETE /v1/sessions/{sessionId}` on `game/api` applies the
   ADR-0039 three-rule cascade across every lobby the session is a
   member of:

   - **Rule 1 — sole-player owner.** When the erased user is the only
     player in a lobby they own, the lobby is deleted outright. The
     `lobbies` row drop cascades to `lobby_players` and
     `lobby_cell_entries` via `ON DELETE CASCADE`, so nothing keyed to
     the lobby survives.
   - **Rule 2 — owner with remaining players.** Ownership transfers to
     the earliest-joined remaining player (deterministic so the
     remaining humans see a stable handoff), the erased user's
     `lobby_players` row is dropped, and every `lobby_cell_entries`
     row authored by them has `written_by_session_id` set to `NULL`.
     The placed letters survive so the puzzle stays coherent for the
     other players; only attribution is removed.
   - **Rule 3 — non-owner.** The `lobby_players` row is dropped and the
     erased user's cell-entry attributions are NULLed. The lobby and
     its owner are otherwise unchanged.

4. Matomo visits are intentionally NOT deleted — they are already
   non-attributable by design. The daily-rotated salted hash means visits
   from prior days cannot be linked, and the fresh local sessionId
   generated after the erase breaks linkage with same-day visits.
   Matomo data persists in aggregate, anonymous form under the 13-month
   retention window.

Aggregate Matomo data (e.g. "10 grids solved on 2026-05-08") remains
because it cannot be attributed to an individual visitor; this is
disclosed in the privacy notice.

### IP addresses

The application never persists an IP address.

- **Matomo** receives the visitor IP at ingest time, immediately
  anonymizes the last two octets, and only the anonymized form is
  stored. CNIL deliberation 2020-091 requires this for the consent
  exemption.
- **Cloudflare** sees IPs because it terminates TLS for the static
  frontend; that is disclosed in the privacy notice and follows
  Cloudflare's DPA, not WordSparrow's.
- **Hetzner** sees IPs at the load-balancer level for the API
  endpoints; these are not logged or persisted by the application.

### Backups

Postgres (CNPG) backups inherit the application's retention windows on
restore. A backup taken on day N includes `puzzle_hint_usage` rows that
were live on day N and may persist in cold storage longer than the
90-day cap. Backups are not actively scrubbed; their retention is
governed by the backup policy in ADR-0010 and the encryption-at-rest
posture of the Storage Box.

## Review

This schedule is reviewed on every PR that introduces a new
`AnalyticsEvent` subtype, a new database table, or a new sub-processor.
The reviewer must confirm the corresponding row exists here.
