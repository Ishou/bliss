# bliss-nats

NATS Server with JetStream — cluster-internal eventing for cross-context
user events (ADR-0049).

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
kubectl -n wordsparrow exec -it deploy/bliss-nats -- \
  nats stream info WORDSPARROW_USER_EVENTS
```

Expected: stream details with subjects `wordsparrow.user.>`, 7-day age
limit, file storage.

## Dump + restore (DR)

```sh
# Dump
kubectl -n wordsparrow exec deploy/bliss-nats -- \
  nats stream backup WORDSPARROW_USER_EVENTS /data/backup
kubectl -n wordsparrow cp wordsparrow/<pod>:/data/backup ./backup

# Restore (on a fresh cluster)
kubectl -n wordsparrow cp ./backup wordsparrow/<pod>:/data/backup
kubectl -n wordsparrow exec deploy/bliss-nats -- \
  nats stream restore /data/backup
```

## Operational checks

- `nats stream report` — per-stream message counts + consumer lag.
- `nats consumer report WORDSPARROW_USER_EVENTS` — per-consumer position +
  pending messages.
- `nats account info` — JetStream storage usage.

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
