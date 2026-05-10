# ADR-0036: PostgreSQL 16 → 17.8 Major-Version Upgrade via cnpg In-Place pg_upgrade

## Status

Accepted

## Context

The WordSparrow API uses a CloudNativePG (cnpg 1.28) managed PostgreSQL cluster
(`wordsparrow-api-pg`) pinned to PG 16.10-bookworm (pinned in PR #349).

The goal is to upgrade to PG 18, but **cnpg's admission webhook enforces
single-major-step upgrades**. A direct 16 → 18 attempt was rejected by the
webhook (`can't upgrade between majors {18 0} and {16 10}`). The upgrade must
therefore land in two PRs: 16 → 17 (this ADR) and 17 → 18 (follow-up).

Staying on PG 16 longer than necessary means:

- Security patches thin out as PG 16 approaches upstream EOL (November 2028);
  exposure widens the closer we get.
- Each deferred major-version step is roughly the same effort; batching them
  into one step is not possible without violating the operator constraint above.

The upgrade must preserve:

- **Distro continuity**: bookworm → bookworm. `pg_upgrade` in the cnpg flow
  requires the same OS base image on both sides; a concurrent distro jump is
  not supported. The trixie base image (default in cnpg 1.29+) is deferred to
  a separate PR.
- **MANIFESTO reproducibility**: image pinned by tag AND digest.

### Why 17.8 specifically

PG 17.0–17.5 carries a known `max_slot_wal_keep_size` bug. 17.6 is the safe
floor per the cnpg operator docs. 17.8 is the current 17.x bookworm release
and incorporates every patch since.

## Decision

Upgrade `postgres.cluster.imageName` in `grid/api/deploy/chart/values.yaml`
from:

```
ghcr.io/cloudnative-pg/postgresql:16.10-bookworm
```

to:

```
ghcr.io/cloudnative-pg/postgresql:17.8-system-bookworm@sha256:9714cbca68836d0a4f5e455d8078afafd60e0751d28944935309be90d9c4ec98
```

A follow-up PR will step from 17.8 → 18.x once this one settles.

### How the upgrade lands (cnpg 1.28 in-place flow)

1. `helm upgrade` on `wordsparrow-api` writes the new `imageName` to the live
   `Cluster` resource.
2. cnpg detects the major-version delta and schedules a
   `<cluster>-primary-major-upgrade` Job.
3. The **entire cluster (primary + replicas) goes offline** for the duration of
   `pg_upgrade`. At our data volume (sessions, hint usage, clue cooldowns,
   puzzle cache) this is seconds-to-minutes.
4. Job completes; pods restart on PG 17.8; cluster returns to
   `Cluster in healthy state`.

Deployment is triggered by running the `Deploy API (k8s)` workflow via
`workflow_dispatch` after merge, supplying the current production app-image
digest. This keeps the deployment on the CI-only path (MANIFESTO: "CI is the
only path to production. No deploying from dev machines.").

### Application compatibility verified

| Component | PG 17 support |
|---|---|
| PostgreSQL JDBC 42.7.11 | ✓ |
| Flyway 12.6.0 | ✓ |
| HikariCP 7.0.2 | ✓ (protocol-agnostic) |
| App SQL | Standard SQL; no version-specific syntax |

### Alternatives considered

- **Jump directly to PG 18** — rejected by cnpg's admission webhook, which
  enforces single-major-step upgrades. Not architecturally possible in one PR.
- **Logical-replication blue-green upgrade** — zero-downtime, but requires
  standing up a parallel cluster, cutover orchestration, and dual-write or
  replication slot management. Not worth the complexity at our scale; the brief
  offline window of `pg_upgrade` is acceptable.
- **Defer until EOL pressure** — delays without benefit; each major-version
  jump is roughly the same effort.
- **Trixie base image simultaneously** — cnpg 1.29 makes trixie the default;
  conflating distro and major-version jumps in one PR makes rollback ambiguous.
  Deferred to its own PR.

## Consequences

**Easier**

- PG 17.8 moves us one step closer to PG 18 and extends the upstream support
  runway.
- Image pinned by tag AND digest: reproducible pulls, deterministic builds.
- Upgrade is fully operator-managed; no manual data migration scripts.

**Harder / different**

- Cluster goes **offline** during `pg_upgrade`. Seconds-to-minutes of downtime.
  A maintenance window must be scheduled before merging.
- PITR does **not** cross the major-version boundary. A fresh base backup must
  be taken immediately after the cluster returns healthy. WAL archives from the
  PG 16 era cannot be used to recover to a point in time after the upgrade.
- Post-upgrade `ANALYZE` is required (`pg_upgrade` does not carry over planner
  statistics). Must be run manually: `kubectl cnpg psql wordsparrow-api-pg -n
  wordsparrow -- wordsparrow -c 'ANALYZE'`.
- A second maintenance window is required for the 17 → 18 follow-up PR.

### Rollback

If `pg_upgrade` fails or the cluster does not return healthy: revert this PR
(`git revert <merge-sha>`) and re-run the `Deploy API (k8s)` `workflow_dispatch`
with the reverted chart. cnpg applies the reverted `imageName`
(`16.10-bookworm@sha256:bf0b0ec764b26fbb9136476ed29ad92abdc1277d0465194b8bf420f0116b974d`)
and initiates a new offline window to revert the in-place upgrade. Document the
failure signature in the `values.yaml` comment block for the next attempt.
