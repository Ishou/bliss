# ADR-0040: Dedicated observability worker topology

## Status

Accepted

## Context

ADR-0027 chose SigNoz as the observability backend and acknowledged that ClickHouse
(+ZooKeeper) is a resource-intensive OLAP engine. In practice, running SigNoz on the
same cx32 worker as the WordSparrow API pods produces a noisy-neighbour problem:
ClickHouse's compaction threads compete with the JVM for CPU time, causing latency
spikes in the API and making it impossible to distinguish "app is slow" from "SigNoz is
ingesting a spike" in the metrics. The cluster footprint is cx22 control plane + cx32
app worker, so there is no scheduling headroom.

### Options considered

| Option | Trade-off |
|---|---|
| **Separate cx32 (or cx22) tainted node for SigNoz** | Extra node cost (~€4–8/month); workload isolation is clean. Opt-in — default `observability_worker_count = 0` so the cost is zero until deliberately enabled. |
| Resource limits + requests on SigNoz pods | Correct but insufficient — ClickHouse can still fully utilise its quota and leave the JVM starved for memory bandwidth / disk I/O on the same host. Doesn't survive a SigNoz upgrade that widens its resource envelope. |
| Managed observability (Grafana Cloud, DataDog, etc.) | Eliminates node ops; adds SaaS cost and potential GDPR complexity. Premature at current scale. Keep as an escape hatch. |

## Decision

Add an **opt-in dedicated observability worker** to the Hetzner cluster module.

- Controlled by `observability_worker_count` (default `0`; set to `1` to enable). The
  variable is validated to `0–5`; values above `1` are meaningless until ClickHouse is
  configured for multi-shard operation, which is not the v1 posture.
- Workers use IP sub-range `10.0.1.30..79` (50 addresses; up to 30 app workers occupy
  `10.0.1.20..`, control planes occupy `10.0.1.10..`).
- Each node is tainted `dedicated=observability:NoSchedule` and labeled
  `bliss.io/role=observability`. Only SigNoz pods (which carry a matching toleration
  in the SigNoz Helm chart values) will schedule there.
- Instance size defaults to `worker_node_size`; override via
  `observability_worker_node_size` if SigNoz grows beyond the shared size.
- Observability workers are **not** FIP holders. The Hetzner floating IP assignment
  is on the app worker (`hcloud_floating_ip_assignment`); observability nodes must not
  bootstrap FIP netplan aliases or DNAT iptables rules (see ADR-0035 and ADR-0012).

## Consequences

**Positive:**

- SigNoz CPU/memory contention with the API is eliminated by construction. The two
  workloads share nothing except the Hetzner private network.
- The feature flag (`observability_worker_count`) keeps the cost zero for environments
  that don't need isolation (staging, local k3d). Only production pays the extra node.
- The taint convention is consistent with the existing manifesto principle on
  least-privilege scheduling: workloads that opt in to a node class declare the
  toleration; workloads that don't, can't land there.

**Negative / trade-offs:**

- ~€4–8/month extra node cost in production when enabled.
- One more node to patch, monitor, and potentially drain. Mitigated by the GitOps
  topology (a `tofu apply` with `observability_worker_count = 0` drains and deletes
  the node cleanly as long as SigNoz is first moved or the stack is scaled down).

**Future ADRs unblocked:**

- If ClickHouse multi-shard becomes necessary (traffic volume), extend
  `observability_worker_count` to `>1`; this ADR already validates up to 5.
- If managed observability (Grafana Cloud, etc.) is adopted, `observability_worker_count`
  returns to `0` and the Terraform resources are deleted — zero footprint left behind.
