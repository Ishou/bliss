# ADR-0041: Replace Bitnami Zookeeper with ClickHouse Keeper

## Status

Proposed (deferred — migration scheduled for a calm operational window).
Dated 2026-05-12.

## Context

ADR-0027 selected **SigNoz** as the observability backend and pulled in the
official SigNoz Helm chart as a subchart of `infra/observability/`. That
chart ships with **Bitnami ZooKeeper** as the coordination layer for the
embedded ClickHouse instance — a default the parent ADR did not override.
At the time of ADR-0027 (2025), ClickHouse Keeper was still labeled
"experimental" inside ClickHouse and SigNoz's chart maintainers carried
that caution forward; ZooKeeper was the safe, well-trodden choice.

What changed since:

- ClickHouse Keeper has been **production-stable since ClickHouse 23.x**
  (2023) and is now the upstream-recommended coordination layer for new
  deployments. The SigNoz 0.122.0 chart we pin (`Chart.yaml`) bundles a
  ClickHouse server new enough to run Keeper either embedded or
  standalone.
- The Altinity ClickHouse operator that the SigNoz chart leans on supports
  Keeper as a first-class option in the CHI spec.

Our specific pain point: Bitnami ZK 3.7.1 + the chart-default
`heapSize: 1024` was the root cause of the recurring memory-ceiling
incident we hit during the observability-stack stabilisation work
(PRs #383, #386, #391). We mitigated by bumping the container limit and
lowering the heap to 512 MB, which stopped the OOM-kills, but the
underlying JVM still costs ~700-900 MB of resident memory on a worker
whose total budget is 4 GB — for a coordination service whose actual
metadata fits in tens of megabytes. That tax is paid every minute of
every day; the heap-tuning PRs treated the symptom, not the cause.

Today's cluster posture (per ADR-0040's dedicated observability worker):
1× ClickHouse instance (Altinity operator-managed CHI), 1× Bitnami
ZooKeeper (single replica, JVM), no replication, no shards.

## Decision

Migrate ZooKeeper → **ClickHouse Keeper, embedded mode** (Keeper runs
inside the existing `clickhouse-server` pod via the operator's `useChKeeper`
toggle; no separate StatefulSet).

Rationale:

- **Same wire protocol.** Keeper speaks the ZooKeeper protocol; ClickHouse
  and the SigNoz operator code paths do not change.
- **Eliminates the JVM entirely.** Frees ~700 MB RAM on the observability
  worker — meaningful at a 4 GB budget.
- **One fewer subchart.** The Bitnami ZK chart and its registry quirks
  (we already had to override `repository: bitnamilegacy/zookeeper` in
  `infra/observability/values.yaml` after Bitnami changed their image
  hosting) stop being our problem.
- **Right-sized for our topology.** 1 ClickHouse instance + 1 ZK replica
  today; embedded Keeper provides equivalent coordination with strictly
  fewer moving parts. Standalone Keeper pods would be the right answer if
  we shard ClickHouse later — at that point we revisit.

Alternatives considered and rejected:

- **Status quo (keep ZK).** Lowest risk, but pays the JVM tax forever and
  keeps a deprecated-by-upstream dependency.
- **Standalone Keeper pods (3-replica quorum).** Production posture for
  large fleets; overkill for a single-replica ClickHouse and would
  *increase* pod count rather than reduce it.

## Procedure

Runbook for the cutover, to be executed during a calm window:

1. **Pre-flight.** Confirm the ClickHouse version pinned by SigNoz 0.122.0
   supports Keeper (>= 22.x; verify via `kubectl exec` →
   `clickhouse-server --version`). Confirm there is no replication
   backlog: `SELECT * FROM system.replication_queue` must be empty.
   Snapshot **both** ClickHouse data and ZK state (PVC snapshot or a
   manual `clickhouse-backup` + `zkCli.sh` snapshot copy).
2. **Pause ingest.** `kubectl scale deploy/observability-signoz-otel-collector
   --replicas=0`. The OTel agents on application pods buffer locally; a
   1-2 min ingest pause is acceptable.
3. **Snapshot ZK.** Exec into the ZK pod and force a snapshot of
   `/var/lib/zookeeper/data`. Copy the latest `snapshot.*` file plus the
   trailing `log.*` files out of the pod.
4. **Convert.** Run `clickhouse-keeper-converter` against the ZK snapshot
   to produce a Keeper-format snapshot:

   ```
   clickhouse-keeper-converter \
     --zookeeper-logs-dir <copied-log-dir> \
     --zookeeper-snapshots-dir <copied-snapshot-dir> \
     --output-dir ./keeper-snapshot
   ```

   Reference:
   <https://clickhouse.com/docs/en/operations/clickhouse-keeper#how-to-migrate-data-from-zookeeper-to-clickhouse-keeper>.
5. **Apply CHI override.** Edit `infra/observability/values-prod.yaml` to
   flip the CHI to embedded Keeper. Preferred path: set
   `signoz.clickhouse.useChKeeper: true` if the SigNoz 0.122 chart
   exposes that key cleanly. Fallback: override the CHI directly via
   `signoz.clickhouse.installation.spec.configuration.zookeeper` to point
   at the embedded Keeper service. `helm upgrade observability ./infra/observability`.
6. **Seed Keeper.** Copy the converted snapshot into the ClickHouse pod
   at `/var/lib/clickhouse/coordination/snapshots/`. Restart the
   `clickhouse-server` pod so Keeper boots from the seeded state.
7. **Verify.** `SELECT * FROM system.zookeeper WHERE path = '/'` against
   the embedded Keeper must return the expected SigNoz znodes. The
   SigNoz UI must render traces/logs/metrics queries normally.
8. **Restore writes.** `kubectl scale deploy/observability-signoz-otel-collector
   --replicas=1`.
9. **Decommission Bitnami ZK.** Set `signoz.clickhouse.zookeeper.enabled:
   false` in `values-prod.yaml`, `helm upgrade`. Retain PVC
   `data-observability-zookeeper-0` for one week as a rollback artefact,
   then delete.

## Risks and mitigations

- **State migration fails mid-swap.** ClickHouse loses metadata;
  replicated DDL breaks. *Mitigation:* full snapshots at step 1 (both CH
  data and ZK state). *Rollback:* re-enable ZK in values, restore the ZK
  snapshot into the ZK PVC, `helm upgrade`. The CHI override at step 5
  is the only irreversible-feeling step; it is in fact a one-line
  revert.
- **Embedded coupling.** A Keeper failure now crashes the
  `clickhouse-server` pod (today, a ZK failure leaves CH running but
  unable to do DDL). *Mitigation:* acceptable at single-replica scale;
  the failure mode is the same kind of outage either way. If/when we
  scale ClickHouse to multiple replicas or shards, switch to standalone
  Keeper pods and revisit in a follow-up ADR.
- **Chart key uncertainty.** SigNoz 0.122 may not expose `useChKeeper`
  on the subchart surface; the operator-level CHI key may be the only
  reliable handle. *Mitigation:* `helm template --debug` the umbrella
  chart before the live cutover and confirm the rendered CHI carries
  the expected `zookeeper:` (or `clickhouse_keeper:`) section. If the
  chart genuinely does not support it, defer until a SigNoz chart bump
  exposes a cleaner key, or fork the override into our umbrella chart
  templates.

## Consequences

**Easier**

- One fewer JVM to operate, monitor, patch.
- One fewer subchart (Bitnami ZK) and its registry/tag quirks to track.
- ~700 MB RAM freed on the observability worker — directly improves
  headroom against the recurring memory ceiling.
- The class of bug "heap silently > container limit" disappears for
  coordination.

**Harder**

- ClickHouse and its coordination are now co-located in a single pod —
  an OOM or crash takes both down simultaneously. Acceptable at our
  scale; flagged for revisit on scale-out.
- Operational muscle memory for "ZK is hung" debugging
  (`zkCli.sh`, four-letter words) shifts to Keeper's CLI
  (`clickhouse-keeper-client`, `system.zookeeper` table).

**Different**

- Monitoring shifts: the Bitnami ZK exporter metrics (jmx, zk_*) go
  away. Replaced by ClickHouse Keeper's own log stream and the
  `system.zookeeper` table. Any SigNoz alert rules targeting the old
  ZK exporter must be rewritten before this ADR is moved to
  `Accepted`.
- The umbrella chart's `values.yaml` comment about
  `bitnamilegacy/zookeeper` becomes dead code and is removed in the
  same PR that flips the toggle.

## References

- ADR-0027 — `docs/adr/0027-observability-backend-signoz.md` (parent,
  selected SigNoz; inherited Bitnami ZK as the chart default).
- ADR-0040 — `docs/adr/0040-observability-dedicated-worker-topology.md`
  (the dedicated worker whose RAM budget this migration relieves).
- ClickHouse Keeper documentation:
  <https://clickhouse.com/docs/en/guides/sre/keeper/clickhouse-keeper>.
- `clickhouse-keeper-converter` migration tool:
  <https://clickhouse.com/docs/en/operations/clickhouse-keeper#how-to-migrate-data-from-zookeeper-to-clickhouse-keeper>.
- `infra/observability/values.yaml` — current ZK override
  (`bitnamilegacy/zookeeper`, `heapSize: 512`).
- `infra/observability/values-prod.yaml` — prod-specific overrides;
  the CHI flip lands here.
- PR #391 — the heap-tuning fix that stopped the OOM-kills but did not
  address the underlying JVM tax this ADR retires.
