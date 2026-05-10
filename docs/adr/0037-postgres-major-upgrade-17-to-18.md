# ADR-0037: PostgreSQL 17.8 → 18.1 Major-Version Upgrade via cnpg In-Place pg_upgrade

## Status

Accepted

## Context

ADR-0036 completed the first step of a two-step upgrade path (PG 16 → 17.8) required
by cnpg 1.28's admission webhook, which enforces single-major-step in-place upgrades.
This ADR covers the follow-up step: 17.8 → 18.1.

The same constraints apply:

- **Distro continuity**: bookworm → bookworm. `pg_upgrade` requires the same OS base
  image on both sides; a concurrent distro jump is not supported.
- **MANIFESTO reproducibility**: image pinned by tag AND digest.
- **PITR boundary**: the major-version boundary is a new PITR boundary. The PG 17.8
  WAL archive cannot be used to recover to a point in time after the upgrade. A fresh
  base backup must be taken immediately post-upgrade.

### Why 18.1 specifically

PG 18.1 is the current 18.x bookworm release on the CNPG image registry and
incorporates all patches since 18.0. 18.0 introduced significant planner improvements
(incremental sort, better partition pruning) and extended the upstream support runway.
Staying on 17.x beyond the second maintenance window carries no benefit and defers the
same effort to the next cycle.

### Application compatibility verified against PG 18

| Component | PG 18 support |
|---|---|
| PostgreSQL JDBC 42.7.11 | ✓ — JDBC protocol is wire-compatible; no PG-18-specific API used |
| Flyway 12.6.0 | ✓ — Flyway 12.x supports PG 18 per the Flyway compatibility matrix |
| HikariCP 7.0.2 | ✓ — protocol-agnostic connection pool; transparent across major versions |
| App SQL | Standard SQL; no version-specific syntax or removed functions |

PG 18 removed several legacy functions deprecated since PG 12 (e.g. `pg_stat_activity`
columns removed in earlier versions). The application schema and queries use none of
these; verified by reviewing all Flyway migrations and the application's query set.

### How the upgrade lands (cnpg 1.28 in-place flow)

Identical to ADR-0036 §"How the upgrade lands":

1. `helm upgrade` (triggered by the `Deploy API (k8s)` CI workflow) writes the new
   `imageName` to the live `Cluster` resource.
2. cnpg detects the major-version delta and schedules a
   `<cluster>-primary-major-upgrade` Job.
3. The **entire cluster (primary + replicas) goes offline** for the duration of
   `pg_upgrade`. At our data volume (sessions, hint usage, clue cooldowns,
   puzzle cache) this is seconds-to-minutes.
4. Job completes; pods restart on PG 18.1; cluster returns to
   `Cluster in healthy state`.

Deployment is triggered by running the `Deploy API (k8s)` workflow via
`workflow_dispatch` after merge, supplying the current production app-image
digest. This keeps the deployment on the CI-only path (MANIFESTO: "CI is the
only path to production. No deploying from dev machines.").

### Alternatives considered

- **Stay on PG 17.x** — defers the same effort without benefit; each major-version
  jump is roughly the same complexity.
- **Logical-replication blue-green upgrade** — zero-downtime, but requires a parallel
  cluster, cutover orchestration, and dual-write or replication slot management. Not
  worth the complexity at our scale; the brief offline window of `pg_upgrade` is
  acceptable.
- **Trixie base image simultaneously** — cnpg 1.29 makes trixie the default;
  conflating distro and major-version jumps in one PR makes rollback ambiguous.
  Deferred to its own PR (same decision as ADR-0036).

## Decision

Upgrade `postgres.cluster.imageName` in `grid/api/deploy/chart/values.yaml`
from:

```
ghcr.io/cloudnative-pg/postgresql:17.8-system-bookworm@sha256:9714cbca68836d0a4f5e455d8078afafd60e0751d28944935309be90d9c4ec98
```

to:

```
ghcr.io/cloudnative-pg/postgresql:18.1-system-bookworm@sha256:61654dc3b1aa2191e083288caf332e60daa342817a64a285283e69e31f1c854d
```

## Consequences

**Easier**

- PG 18.1 extends the upstream support runway and closes the two-step upgrade path
  begun in ADR-0036.
- Image pinned by tag AND digest: reproducible pulls, deterministic builds.
- Upgrade is fully operator-managed; no manual data migration scripts.

**Harder / different**

- Cluster goes **offline** during `pg_upgrade`. Seconds-to-minutes of downtime.
  A maintenance window must be scheduled before merging.
- PITR does **not** cross the major-version boundary. A fresh base backup must
  be taken immediately after the cluster returns healthy. WAL archives from the
  PG 17.8 era cannot be used to recover to a point in time after the upgrade.
- Post-upgrade `ANALYZE` is required (`pg_upgrade` does not carry over planner
  statistics). Must be run manually: `kubectl cnpg psql wordsparrow-api-pg -n
  wordsparrow -- wordsparrow -c 'ANALYZE'`.

### Rollback

If `pg_upgrade` fails or the cluster does not return healthy: revert this PR
(`git revert <merge-sha>`) and re-run the `Deploy API (k8s)` `workflow_dispatch`
with the reverted chart. cnpg applies the reverted `imageName`
(`17.8-system-bookworm@sha256:9714cbca68836d0a4f5e455d8078afafd60e0751d28944935309be90d9c4ec98`)
and initiates a new offline window to revert the in-place upgrade. Document the
failure signature in the `values.yaml` comment block for the next attempt.
