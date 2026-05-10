# ADR-0038: Add SigNoz k8s-infra subchart for per-pod and per-node metrics

## Status

Accepted

## Context

ADR-0027 deployed SigNoz via a Helm umbrella chart at `infra/observability/`. SigNoz's
bundled `otel-collector` is a **Deployment** (a single replica): it listens for OTLP
traffic pushed from application pods and from the browser ingest ingress (ADR-0033).

That topology leaves three categories of metrics unscraped:

| Missing metric category | Root cause |
|---|---|
| Per-pod CPU / memory (container resource utilization) | `kubeletstats` receiver must run on the same node as the kubelet it scrapes — a single Deployment replica cannot scrape all nodes |
| Per-node host metrics (CPU, disk, network, filesystem) | `hostmetrics` receiver reads `/proc` and `/sys` on the node; same constraint as above |
| Cluster-state metrics (deployment replicas, pod phases, restarts) | `k8s_cluster` receiver polls the Kubernetes API — technically runnable as a Deployment, but not present in the bundled collector config |

The gap became visible during an incident on 2026-05-10: the `grid-api` pod was being
throttled at its 1 vCPU limit during a CSP solver request, but no pod resource graph
existed in SigNoz — diagnosis required `kubectl top pod` out-of-band.

SigNoz publishes a companion Helm chart, **k8s-infra**, specifically designed to fill
this gap. It deploys:

- An **otel-agent DaemonSet** on every node (kubeletstats + hostmetrics + filelog
  receivers).
- A **singleton otel-deployment Deployment** (k8s_cluster receiver).

Both forward scraped metrics to the bundled SigNoz OTel collector over OTLP/gRPC
(`observability-signoz-otel-collector.observability.svc.cluster.local:4317`) — the same
in-cluster Service the application pods already push to. No additional ingress or Service
is required.

### Options considered

**Option A — Add `kubeletstats` / `hostmetrics` / `k8s_cluster` receivers directly to
the bundled SigNoz otel-collector Deployment.**

Rejected. `kubeletstats` must run co-located with the kubelet on each node (`hostNetwork:
true` or node affinity). A single Deployment replica scraping `localhost:10250` covers
only the node it lands on — multi-node coverage is impossible without a DaemonSet.
`hostmetrics` has the same per-node constraint.

**Option B — Replace the bundled SigNoz collector with k8s-infra's otel-agent.**

Rejected. The two collectors have different jobs: the bundled one is the OTLP ingest
target for application spans and browser-side traces (ADR-0033); the k8s-infra agents
are scrapers. Merging them collapses a push-ingest path and multiple scrape paths into
one config, making lifecycle management harder. SigNoz's documented topology keeps them
separate, and the resource overhead of running both is modest (50 m CPU / 128 Mi RAM
requested per agent pod).

**Option C — Add SigNoz k8s-infra as a Helm subchart (this decision).**

Accepted. Adding k8s-infra as a subchart in `infra/observability/Chart.yaml` gated by
`k8s-infra.enabled` keeps the deployment atomic (one `helm upgrade`), uses SigNoz's
own supported topology, and keeps the DaemonSet off local development environments
(`k8s-infra.enabled: false` in `values.yaml`; `true` only in `values-prod.yaml`).

## Decision

Add `k8s-infra` version `0.13.5` from `https://charts.signoz.io` as a conditional
subchart of the `infra/observability` umbrella chart (alongside the existing `signoz`
subchart). Enable it in `values-prod.yaml` with:

- `clusterName: wordsparrow-prod` (standard `k8s.cluster.name` OTel attribute)
- `otelCollectorEndpoint` pointing to the in-cluster SigNoz collector Service
- `otelInsecure: true` (cluster-internal, no TLS needed)
- Resource requests 50m / 128Mi, limits 200m / 256Mi per agent pod (otel-agent DaemonSet
  and otel-deployment Deployment), sized for the cx33 worker pool

Disable it by default in `values.yaml` so `make dev` does not spin a DaemonSet on local
nodes.

## Consequences

**Positive:**

- SigNoz **Infrastructure → Kubernetes** tab and per-pod CPU/memory graphs are
  populated; the throttling that caused the 2026-05-10 grid-api 422 incident would have
  been visible directly from the UI.
- The k8s-infra DaemonSet lifecycle (enable/disable, version bump) travels with the
  observability umbrella chart — one `helm upgrade` rolls both subcharts.
- No application-code changes; no new ingress; no new Kubernetes Service.

**Negative / trade-offs:**

- A DaemonSet runs on every production node. Pod overhead is 50m CPU / 128Mi RAM at
  request, 200m / 256Mi at limit — acceptable on cx33 workers. Monitor with the very
  metrics it exposes; revisit resource limits if the metric cardinality grows.
- k8s-infra chart versions are independent of the main `signoz` chart version. Bumps
  require checking `helm search repo signoz/k8s-infra` separately.

**Rollback:**

Set `k8s-infra.enabled: false` in `values-prod.yaml` and run `helm upgrade`. The
DaemonSet and Deployment are removed; no other state to clean up. No application
behaviour changes.
