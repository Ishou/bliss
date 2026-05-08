# Data Retention Schedule

> Authoritative list of how long Bliss keeps each piece of data and how it
> is enforced. Any new processing activity introduced by a feature must add
> a row here as a precondition to merge.

## Schedule

| Data | Storage | Retention | Enforcement |
|---|---|---|---|
| `sessionId` (UUID v7) | Browser `localStorage` | Until the user clears browser storage or clicks "Erase my data" | User-controlled; see ADR-0021 |
| Pseudonym | Browser `localStorage` | Same as `sessionId` | User-controlled |
| `puzzle_hint_usage` rows | Postgres (CNPG) | **90 days from `updated_at`** | k8s CronJob (Phase 7), nightly `DELETE WHERE updated_at < now() - interval '90 days'` |
| Multiplayer lobby state | `game/api` JVM heap | Evicted after 30 min idle, or on close | ADR-0018 §"State: in-memory" |
| Cell entries during a multiplayer game | `game/api` JVM heap | Lifetime of the lobby | ADR-0018 |
| Matomo visit + event data | MariaDB (k3s, dedicated to Matomo) | **13 months** | Matomo admin UI: Privacy → Anonymize previous logs |
| Matomo aggregated reports | MariaDB | Indefinite (already aggregated, non-identifying) | n/a |
| Application logs (Ktor `CallLogging`) | stdout, scraped to log store TBD | Pending observability ADR-0007 | Out of scope here |
| Cloudflare access logs | Cloudflare-side | Per Cloudflare's own retention policy | Outside Bliss control |

## Special cases

### Erasure on user request

When a user clicks "Erase my data" (Phase 6), the following deletions
happen in one HTTP request:

1. Frontend: `localStorage.clear()` for the Bliss keys.
2. Backend: `DELETE /v1/sessions/{sessionId}` on `grid/api` removes all
   `puzzle_hint_usage` rows for that session.
3. Backend: same handler calls Matomo's `Live.deleteVisits` filtered by
   the day's rotated hash for the session.

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
  Cloudflare's DPA, not Bliss's.
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
