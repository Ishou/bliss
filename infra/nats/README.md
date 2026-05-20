# bliss-nats

NATS Server with JetStream — cluster-internal eventing for cross-context
user events (ADR-0049).

## Prerequisites

- **Prometheus Operator CRDs** (only when `alerts.enabled=true`). The
  `alerts` block renders a `monitoring.coreos.com/v1 PrometheusRule`
  and will fail `helm install` with `no matches for kind
  "PrometheusRule"` if the CRDs are absent. See
  https://github.com/prometheus-operator/prometheus-operator.
  `infra/platform/` does not yet ship the operator; `values-prod.yaml`
  defaults `alerts.enabled` to `false`. See the **FOLLOW_UP** note
  under `alerts.enabled` below — flip it on once the operator (or its
  CRDs alone) lands.
- **Storage class** — `values-prod.yaml` uses `hcloud-volumes`. See
  `docs/local-development.md` and `docs/deploy.md` for the equivalent
  k3d / Hetzner provisioning.

### 2026-05-20 prod incident note

The chart was previously not installable due to five chart bugs (fixed
in PR #556) plus two hook/ordering bugs (this PR: `alerts.enabled=true`
default + `stream-specs` ConfigMap shipped as a post-install hook,
which raced its consumer Job). After this PR, the prod install is
one-shot:

```sh
KUBECONFIG=~/.kube/wordsparrow-prod helm install bliss-nats ./infra/nats \
  -n wordsparrow \
  -f ./infra/nats/values.yaml -f ./infra/nats/values-prod.yaml
```

No `--set` overrides needed.

## Install

```sh
helm install bliss-nats infra/nats/ \
  -n wordsparrow --create-namespace \
  -f infra/nats/values-prod.yaml \
  --set image.digest=sha256:<resolve at deploy time>
```

The chart deploys a single-replica `StatefulSet` with a PVC for JetStream
stream data, a `ClusterIP` Service (port 4222 for clients, 8222 for
monitoring), and a post-install Job that declares the
`WORDSPARROW_USER_EVENTS` stream.

## Verify

```sh
kubectl -n wordsparrow exec -it bliss-nats-0 -- \
  nats stream info WORDSPARROW_USER_EVENTS
```

Expected: stream details with subjects `wordsparrow.user.>`, 7-day age
limit, file storage.

## Dump + restore (DR)

```sh
# Dump
kubectl -n wordsparrow exec bliss-nats-0 -- \
  nats stream backup WORDSPARROW_USER_EVENTS /data/backup
kubectl -n wordsparrow cp wordsparrow/bliss-nats-0:/data/backup ./backup

# Restore (on a fresh cluster)
kubectl -n wordsparrow cp ./backup wordsparrow/bliss-nats-0:/data/backup
kubectl -n wordsparrow exec bliss-nats-0 -- \
  nats stream restore /data/backup
```

## Operational checks

- `nats stream report` — per-stream message counts + consumer lag.
- `nats consumer report WORDSPARROW_USER_EVENTS` — per-consumer position +
  pending messages.
- `nats account info` — JetStream storage usage.

## Metrics + DLQ + alerts

The chart can ship three additional pieces gated by values flags. All
three default off; `values-prod.yaml` flips them on.

### `metricsExporter.enabled`

Adds a `prometheus-nats-exporter` sidecar to the StatefulSet that scrapes
the NATS monitor port (8222, in-pod localhost) and re-exposes the
counters as Prometheus-format metrics on port 7777 (`metrics`). The
ClusterIP Service surfaces that port; the NetworkPolicy is unchanged —
no app pod is the originator of metric scrapes, so no new `from` rule is
needed for the existing app set.

Pod annotations (`prometheus.io/scrape=true`, `prometheus.io/port=7777`,
`prometheus.io/path=/metrics`) advertise the endpoint to a
discovery-based scraper (SigNoz's bundled OTel collector with a
`prometheus_simple` receiver, or any other annotation-aware scrape
config). If the cluster's collector isn't yet configured to pick those
up, follow-up: add a `prometheus` receiver to
`infra/observability/values.yaml`'s `signoz.otelCollector.config` that
honors the pod annotations.

The exporter image is pinned by tag (`0.15.0`) in defaults; the CD
workflow resolves the digest at deploy time via
`--set metricsExporter.image.digest=sha256:<resolved>`. **FOLLOW_UP**:
once the deploy workflow runs, capture the resolved digest into
`values-prod.yaml` as a non-placeholder so subsequent
`helm template` diffs are deterministic.

### `dlq.enabled`

Creates a second JetStream stream `WORDSPARROW_USER_EVENTS_DLQ` with
subject `wordsparrow.dlq.>` and a 30-day retention. The subject tree is
intentionally distinct from the primary `wordsparrow.user.>` tree so
JetStream does not reject the second stream for subject overlap. The
stream is provisioned by the same bootstrap Job as the primary stream
(idempotent re-run on chart upgrade).

Publishing to the DLQ subject is consumer-side: each context subscribes
to its own JetStream MaxDeliveries advisory
(`$JS.EVENT.ADVISORY.MAX_DELIVERIES.<stream>.<consumer>`) and republishes
the failed message envelope under `wordsparrow.dlq.<original-subject>`.

### `alerts.enabled`

Drops a `monitoring.coreos.com/v1 PrometheusRule` named
`<release>-alerts` with three rules:

| Alert | Expr | For | Severity |
| --- | --- | --- | --- |
| `BlissNatsConsumerLagWarning` | `nats_consumer_num_pending > 100` | 5m | warning |
| `BlissNatsConsumerLagCritical` | `nats_consumer_num_pending > 1000` | 1m | critical |
| `BlissNatsDlqNonEmpty` | `nats_stream_messages{stream_name="WORDSPARROW_USER_EVENTS_DLQ"} > 0` | 1m | warning |

Requires the Prometheus operator (or a compatible CRD watcher) installed
in-cluster. If `monitoring.coreos.com/v1` isn't present, set
`alerts.enabled=false` until the operator lands; the CR is otherwise
inert (no controller, no consumer).

## NetworkPolicy

The chart ships a `NetworkPolicy` restricting ingress on the NATS service
to identity-api / game-api / grid-api pods. The stream-init Job's pod is
labelled with the chart's own `app.kubernetes.io/name` so it can connect
during install.

## Operations: stream + consumer

Producer connection (Kotlin, via `io.nats:jnats`):

```kotlin
val nats = Nats.connect("nats://bliss-nats.wordsparrow:4222")
val js = nats.jetStream()
js.publish("wordsparrow.user.deleted", payloadJsonBytes)
```

Consumer (durable, explicit-ack):

```kotlin
val sub = js.subscribe(
    "wordsparrow.user.deleted",
    PushSubscribeOptions.builder()
        .durable("game-api-user-deleted")
        .build(),
)
val msg = sub.nextMessage(Duration.ofSeconds(5))
// ... handle msg ...
msg.ack()
```
