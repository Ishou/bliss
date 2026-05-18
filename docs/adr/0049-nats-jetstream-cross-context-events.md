# ADR-0049: Lightweight cross-context eventing via NATS JetStream

## Status

Accepted

## Context

Phase 6 of the OAuth2 identity work needs cross-context user events. When a
user deletes their account on identity-api, game-api must anonymize their
lobby seats (GDPR Art. 17 — no identifying trace). When a user renames their
display name, game-api must refresh the cached pseudonym on their active
lobby seats. Grid-api will add similar consumers in 6b for hint history.

The current `UserDeletedBroadcaster` port has an in-memory adapter only;
production needs a real transport. The Phase 6c spec evaluated three options:

1. **Cluster-internal HTTP fan-out** — identity-api POSTs to game-api's
   ClusterIP service. Simple, no new infrastructure. Trade-offs:
   - Producer must know the topology (which consumers exist + their service
     names + paths).
   - Best-effort by construction (HTTP retries are crude); GDPR-grade
     delivery needs synchronous-or-abort, which couples publisher to
     consumer availability.
   - Multi-subscriber fan-out (when grid-api joins) means N round-trips per
     event.

2. **Postgres LISTEN/NOTIFY** — rejected because identity-api + game-api
   don't share a Postgres instance (game-api is in-memory per ADR-0018 §3).

3. **NATS JetStream** — cluster-internal message broker with stream
   persistence. Producers publish to subjects; consumers subscribe with
   explicit-ack semantics. JetStream persists the stream (file storage,
   age-based retention) so transient consumer downtime is recoverable.

## Decision

Stand up NATS Server 2.10 with JetStream in the `wordsparrow` namespace.
Single replica (alpha; upgrade to 3 when the cluster grows). ClusterIP
service, no Ingress, no LoadBalancer. PVC for JetStream stream data.

One stream `WORDSPARROW_USER_EVENTS` covering subjects
`wordsparrow.user.*`. Retention `MaxAge=7d`, storage `file`, replicas `1`,
discard policy `old`.

Producers (identity-api today; future contexts later) publish to subjects.
Consumers (game-api today, grid-api in Phase 6b) subscribe with explicit
acknowledgment. NATS auth: anonymous publish/subscribe within the cluster
network; `NetworkPolicy` (defense-in-depth) restricts which service
accounts can reach the NATS service.

GDPR-critical events (`user.deleted`) use **publish with ack required**:
the producer blocks on the JetStream ack before declaring success. If the
ack times out, the originating use case rolls back. JetStream's persistence
covers consumer downtime — once acked, the event WILL be delivered even
if every consumer is down at publish time.

Best-effort events (`user.renamed`) use **publish without ack**: the
producer returns immediately. JetStream still persists; transient consumer
downtime is recovered on reconnect.

The Kotlin client is `io.nats:jnats:2.20.6` (latest 2.20.x as of
2026-05-18). Pinning the minor line so renovate/Dependabot can bump the
patch without an ADR amendment.

## Consequences

**Easier:**
- New consumer contexts (Phase 6b grid-api) subscribe without producer
  changes. Decoupling.
- Durable retry semantics out of the box (vs hand-rolled retry on HTTP).
- Consumer downtime tolerated — events queue, redelivery on reconnect.
- One transport for every future cross-context event (session revocation,
  user creation, etc.).

**Harder:**
- New infrastructure piece to operate. Monitoring NATS health, stream
  storage, consumer lag — all new ops surface. v1 has no Prometheus
  exporter or alerting; manual `nats stream report`. Real observability
  lands in a follow-up phase.
- Auth model is "anonymous on the cluster network" for alpha. Production
  hardening: per-service NATS accounts + JWT signing. Deferred.
- Stream replicas = 1; NATS pod outage = events queued until restart. Not
  a problem for alpha (no SLO yet); upgrade to 3 replicas when traffic
  warrants.
- Adds the `io.nats:jnats:2.20.6` dependency to identity-api and game-api
  (~3MB JAR each).

**Different:**
- Publisher/consumer authority lives in NATS topology (NetworkPolicy +
  subject naming), NOT in HTTP cookie / token / shared-secret. Different
  threat model — anyone with cluster-pod-network access can publish or
  consume. The defense is the kubernetes network boundary, not the
  application layer.
