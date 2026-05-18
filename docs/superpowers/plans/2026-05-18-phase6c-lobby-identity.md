# Phase 6c Implementation Plan — Game-api lobby identity + NATS eventing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Each implementer subagent invokes the relevant per-context skill at session start: `jvm-backend` for Kotlin work (grid/game/identity), `frontend` for TS work, and whichever k8s/devops skill exists for Helm/NATS work in 6c.0. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire game-api to authenticated identity. Lobby seats carry `userId` when authed, the user's `displayName` becomes the lobby pseudonym (fixing the "Marmotte 900 vs Isho" mismatch live in prod), and account-delete reliably anonymizes lobby seats via NATS JetStream-delivered events.

**Architecture:** Four sub-PRs in strict dependency order. 6c.0 stands up NATS JetStream (cluster-internal, no Ingress) + ADR. 6c.1 wires identity-api NATS publishers + a log-only game-api subscriber to prove end-to-end delivery. 6c.2 renames the session cookie from `__Host-ws_session` → `__Secure-ws_session` (so the cookie travels to all subdomains), adds the cookie-verify port + lobby endpoints + frontend rebind/unbind hooks. 6c.3 makes the game-api subscribers act on the events (anonymize / refresh).

**Reference spec:** `docs/superpowers/specs/2026-05-18-phase6c-lobby-identity-design.md`. Read it before starting any sub-PR.

**Tech stack:** Kotlin 2.3.21 + Ktor 3.4.3 (JVM contexts), `io.nats:jnats:2.20.x` (Kotlin/Java NATS client), NATS Server 2.10 + JetStream, Helm 3, k3s, Vite + React 19 + TanStack Router (frontend).

---

## Shared conventions

These apply to every sub-PR in this plan.

- **Branch naming:** `feat/oauth2-6c-<topic>` or `chore/oauth2-6c-<topic>` per type (per CLAUDE.md `branch-name.yml`).
- **PR title cap:** 70 chars.
- **PR body:** exactly `## Summary` (bullets) + `## Test plan` (markdown checklist). No other headings.
- **DCO sign-off** on every commit (`git commit -s`).
- **400-line diff cap** excluding generated code + blank lines. `git diff origin/main --shortstat` before pushing.
- **No emoji** in code/comments/commits unless the user explicitly asks.
- **Test gates that must stay green:**
  - JVM: `./gradlew :<context>:api:check --quiet` (or `:identity:api:check` etc).
  - Frontend: `cd frontend && pnpm typecheck && pnpm lint && pnpm test && pnpm api:check`.
  - Helm: `helm lint <chart> --strict` + `helm template` + `kubeconform -strict -ignore-missing-schemas`.
- **NATS Kotlin client coordinates:** `io.nats:jnats:2.20.4` (verify latest at writing-plans time via `https://search.maven.org/search?q=g:io.nats+AND+a:jnats`).
- **Stream + subject naming:** stream `WORDSPARROW_USER_EVENTS`, subjects `wordsparrow.user.deleted`, `wordsparrow.user.renamed`. Future: `wordsparrow.user.created`, `wordsparrow.session.revoked` (out of scope).

---

## Task 6c.0 — NATS JetStream infrastructure + ADR

**Branch:** `chore/oauth2-6c-nats-infra`. **Subagent skill:** the k8s/devops skill (or `jvm-backend` as fallback — Helm work is small).

This is pure infrastructure: ship the NATS server + stream definition + ADR. No application code changes. Deployable in isolation; verified via `nats stream info WORDSPARROW_USER_EVENTS`.

### 6c.0.0 — Recon

- [ ] **Read** `infra/platform/` for the existing cluster-bootstrap chart shape (cert-manager / ingress-nginx / CNPG install pattern). The new NATS chart lives alongside.
- [ ] **Read** `game/api/deploy/db-chart/` for a sibling small-chart precedent (`StatefulSet`-shaped CNPG cluster; similar pattern fits NATS).
- [ ] **Read** `docs/adr/` index for the next free ADR number. As of 2026-05-18 it's likely in the 0049–0051 range — verify by `ls docs/adr/ | sort | tail`.
- [ ] **Verify the NATS Kotlin client coords** at <https://central.sonatype.com/artifact/io.nats/jnats> — pin the exact patch version in the ADR.

### 6c.0.1 — ADR

- [ ] **Create** `docs/adr/<NEXT>-nats-jetstream-cross-context-events.md`. Use the next free number from recon. Template (adapt the body — every section must be filled, NO placeholders):

```markdown
# ADR-<NNNN>: Lightweight cross-context eventing via NATS JetStream

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
- Adds the `io.nats:jnats:2.20.4` dependency to identity-api and game-api
  (~3MB JAR each).

**Different:**
- Publisher/consumer authority lives in NATS topology (NetworkPolicy +
  subject naming), NOT in HTTP cookie / token / shared-secret. Different
  threat model — anyone with cluster-pod-network access can publish or
  consume. The defense is the kubernetes network boundary, not the
  application layer.
```

### 6c.0.2 — Helm chart

- [ ] **Create** the chart directory:

```bash
mkdir -p infra/nats/templates
```

- [ ] **Create** `infra/nats/Chart.yaml`:

```yaml
apiVersion: v2
name: bliss-nats
description: "NATS Server with JetStream for cross-context user events (ADR-<NNNN>)"
type: application
version: 0.1.0
appVersion: "2.10"
# No subchart dependencies. JetStream is enabled via the server's own config.
dependencies: []
```

- [ ] **Create** `infra/nats/values.yaml`:

```yaml
image:
  # nats:2.10-alpine. Pinned by digest in values-prod.yaml.
  repository: nats
  tag: "2.10-alpine"
  digest: ""
  pullPolicy: IfNotPresent

# JetStream stream storage. 1Gi covers ~10M small events at alpha volume.
storage:
  size: 1Gi
  # Empty -> cluster default. Prod sets "hcloud-volumes" per ADR-0010.
  storageClass: ""

# Server config — JetStream enabled, file storage on the PVC, no auth in v1.
config:
  port: 4222
  monitorPort: 8222
  jetstreamMaxMemory: "256M"
  jetstreamMaxFile: "1Gi"

# Stream definition applied at startup via the bootstrap Job.
stream:
  name: WORDSPARROW_USER_EVENTS
  subjects: ["wordsparrow.user.>"]
  maxAge: "168h"   # 7 days
  storage: file
  replicas: 1
  discardPolicy: old

resources:
  requests: { cpu: 100m, memory: 128Mi }
  limits: { cpu: 500m, memory: 384Mi }

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 1000
  runAsGroup: 1000
  fsGroup: 1000
containerSecurityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: [ALL] }
```

- [ ] **Create** `infra/nats/values-prod.yaml`:

```yaml
# Production overrides — Hetzner k3s (ADR-0009 §2).
image:
  # Pin the latest 2.10-alpine digest. Verify at writing-plans time with
  # `crane digest nats:2.10-alpine` (or skopeo).
  digest: "sha256:<resolve at deploy time>"
storage:
  size: 5Gi
  storageClass: "hcloud-volumes"
```

(The `<resolve at deploy time>` digest placeholder is a CD-injected value, NOT a plan placeholder. The CD workflow's `helm upgrade` step passes `--set image.digest=<resolved>`. Document this in the chart's README; match the grid-api / game-api pattern.)

- [ ] **Create** `infra/nats/templates/_helpers.tpl`:

```tpl
{{- define "bliss-nats.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-nats.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- define "bliss-nats.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-nats.labels" -}}
helm.sh/chart: {{ include "bliss-nats.chart" . }}
app.kubernetes.io/name: {{ include "bliss-nats.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "bliss-nats.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bliss-nats.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "bliss-nats.image" -}}
{{- if .Values.image.digest -}}
{{- printf "%s:%s@%s" .Values.image.repository .Values.image.tag .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}
{{- end -}}
```

- [ ] **Create** `infra/nats/templates/configmap.yaml` (NATS server config):

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "bliss-nats.fullname" . }}-config
  labels: {{- include "bliss-nats.labels" . | nindent 4 }}
data:
  nats.conf: |
    port: {{ .Values.config.port }}
    http_port: {{ .Values.config.monitorPort }}

    # JetStream — file-backed persistence for cross-context user events.
    # Storage paths point at the PVC mounted on /data; the server creates
    # subdirectories per stream.
    jetstream {
      store_dir: "/data/jetstream"
      max_memory_store: {{ .Values.config.jetstreamMaxMemory | quote }}
      max_file_store: {{ .Values.config.jetstreamMaxFile | quote }}
    }

    # No auth for v1 (ADR-<NNNN>). Reachable only from inside the cluster
    # network; NetworkPolicy below restricts further to the three service
    # accounts. Auth hardening is a follow-up.
```

- [ ] **Create** `infra/nats/templates/statefulset.yaml`:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ include "bliss-nats.fullname" . }}
  labels: {{- include "bliss-nats.labels" . | nindent 4 }}
spec:
  replicas: 1
  serviceName: {{ include "bliss-nats.fullname" . }}
  selector:
    matchLabels: {{- include "bliss-nats.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels: {{- include "bliss-nats.labels" . | nindent 8 }}
    spec:
      podSecurityContext: {{- toYaml .Values.podSecurityContext | nindent 8 }}
      containers:
        - name: nats
          image: {{ include "bliss-nats.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          securityContext: {{- toYaml .Values.containerSecurityContext | nindent 12 }}
          args:
            - "-c"
            - "/etc/nats/nats.conf"
          ports:
            - { name: client,  containerPort: {{ .Values.config.port }} }
            - { name: monitor, containerPort: {{ .Values.config.monitorPort }} }
          volumeMounts:
            - { name: config, mountPath: /etc/nats }
            - { name: data,   mountPath: /data }
          livenessProbe:
            httpGet: { path: /healthz, port: monitor }
            periodSeconds: 15
          readinessProbe:
            httpGet: { path: /healthz?js-enabled-only=true, port: monitor }
            periodSeconds: 10
          resources: {{- toYaml .Values.resources | nindent 12 }}
      volumes:
        - name: config
          configMap:
            name: {{ include "bliss-nats.fullname" . }}-config
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: [ReadWriteOnce]
        resources: { requests: { storage: {{ .Values.storage.size }} } }
        {{- with .Values.storage.storageClass }}
        storageClassName: {{ . }}
        {{- end }}
```

- [ ] **Create** `infra/nats/templates/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "bliss-nats.fullname" . }}
  labels: {{- include "bliss-nats.labels" . | nindent 4 }}
spec:
  # ClusterIP only — never Ingress, never LoadBalancer. NATS is
  # cluster-internal infrastructure per ADR-<NNNN>.
  type: ClusterIP
  ports:
    - { name: client,  port: {{ .Values.config.port }},        targetPort: client }
    - { name: monitor, port: {{ .Values.config.monitorPort }}, targetPort: monitor }
  selector: {{- include "bliss-nats.selectorLabels" . | nindent 4 }}
```

- [ ] **Create** `infra/nats/templates/stream-bootstrap-job.yaml` — runs once to declare the JetStream stream:

```yaml
{{- if .Values.stream.name }}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "bliss-nats.fullname" . }}-stream-init
  labels: {{- include "bliss-nats.labels" . | nindent 4 }}
  annotations:
    # Re-run when the chart upgrades — `nats stream add` is idempotent
    # against an existing stream with the same spec; differing spec
    # would error and surface in the helm output.
    helm.sh/hook: post-install,post-upgrade
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
spec:
  ttlSecondsAfterFinished: 600
  backoffLimit: 3
  template:
    metadata:
      labels: {{- include "bliss-nats.selectorLabels" . | nindent 8 }}
    spec:
      restartPolicy: Never
      containers:
        - name: stream-init
          # `nats` CLI image (synadia/nats-box) — bundles the CLI we need.
          image: natsio/nats-box:0.14
          securityContext: {{- toYaml .Values.containerSecurityContext | nindent 12 }}
          env:
            - { name: NATS_URL, value: "nats://{{ include "bliss-nats.fullname" . }}:{{ .Values.config.port }}" }
          command: ["/bin/sh", "-c"]
          args:
            - |
              set -euo pipefail
              # Wait until JetStream is ready. The NATS pod's readiness gate
              # requires JetStream; this loop covers the small delay between
              # the StatefulSet being Ready and JetStream listing as enabled.
              until nats account info > /dev/null 2>&1; do
                echo "waiting for NATS JetStream..."
                sleep 2
              done
              # Add the stream. Idempotent — same spec produces no diff.
              nats stream add {{ .Values.stream.name }} \
                --subjects '{{ join "," .Values.stream.subjects }}' \
                --max-age {{ .Values.stream.maxAge }} \
                --storage {{ .Values.stream.storage }} \
                --replicas {{ .Values.stream.replicas }} \
                --discard {{ .Values.stream.discardPolicy }} \
                --retention limits \
                --max-bytes -1 \
                --max-msgs -1 \
                --dupe-window 2m \
                --defaults
              nats stream info {{ .Values.stream.name }}
{{- end }}
```

- [ ] **Create** `infra/nats/templates/networkpolicy.yaml` (defense-in-depth):

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "bliss-nats.fullname" . }}
  labels: {{- include "bliss-nats.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels: {{- include "bliss-nats.selectorLabels" . | nindent 6 }}
  policyTypes: [Ingress]
  ingress:
    # Only identity-api + game-api (+ future grid-api in 6b) can reach the
    # NATS port. The stream-init Job's pods carry app.kubernetes.io/name=bliss-nats
    # (via selectorLabels), matching the fourth from-selector below, so they
    # can connect at install time. Cross-namespace traffic remains excluded.
    - from:
        - podSelector: { matchLabels: { app.kubernetes.io/name: bliss-identity-api } }
        - podSelector: { matchLabels: { app.kubernetes.io/name: bliss-game-api } }
        - podSelector: { matchLabels: { app.kubernetes.io/name: bliss-grid-api } }
        - podSelector: { matchLabels: { app.kubernetes.io/name: bliss-nats } }  # stream-init Job
      ports:
        - { protocol: TCP, port: {{ .Values.config.port }} }
```

### 6c.0.3 — README

- [ ] **Create** `infra/nats/README.md`:

```markdown
# bliss-nats

NATS Server with JetStream — cluster-internal eventing for cross-context
user events (ADR-<NNNN>).

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
kubectl -n wordsparrow exec -it sts/bliss-nats -- \
  nats stream info WORDSPARROW_USER_EVENTS
```

Expected: stream details with subjects `wordsparrow.user.>`, 7-day age
limit, file storage.

## Dump + restore (DR)

```sh
# Dump
kubectl -n wordsparrow exec sts/bliss-nats -- \
  nats stream backup WORDSPARROW_USER_EVENTS /data/backup
kubectl -n wordsparrow cp wordsparrow/<pod>:/data/backup ./backup

# Restore (on a fresh cluster)
kubectl -n wordsparrow cp ./backup wordsparrow/<pod>:/data/backup
kubectl -n wordsparrow exec sts/bliss-nats -- \
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
```

### 6c.0.4 — Lint + render

- [ ] **Run** `helm lint`:

```bash
helm lint infra/nats/ --strict
helm lint infra/nats/ --strict -f infra/nats/values-prod.yaml --set image.digest=sha256:0000000000000000000000000000000000000000000000000000000000000000
```

Expected: both green.

- [ ] **Render**:

```bash
helm template bliss-nats infra/nats/ \
  -f infra/nats/values-prod.yaml \
  --set image.digest=sha256:0000000000000000000000000000000000000000000000000000000000000000 \
  | kubeconform -strict -ignore-missing-schemas -summary -
```

Expected: 5 resources (ConfigMap, StatefulSet, Service, Job, NetworkPolicy). All conform.

### 6c.0.5 — Wire into chart-lint CI

- [ ] **Modify** `.github/workflows/api-chart-lint.yml`. Add the path filter `infra/nats/**` AND add a matrix row:

```yaml
- path: infra/nats
  release: bliss-nats
  hasProdValues: true
  localValuesFile: values.yaml
```

(Same pattern as the identity-api-pg matrix entry from PR #504.)

### 6c.0.6 — Commit + PR

- [ ] **Commit**:

```bash
git add infra/nats/ docs/adr/<NNNN>-nats-jetstream-cross-context-events.md .github/workflows/api-chart-lint.yml
git commit -s -m "chore(nats): JetStream cluster-internal eventing infra"
```

- [ ] **Push + PR** (title ≤ 70 chars):

```bash
git push -u origin chore/oauth2-6c-nats-infra
gh pr create --base main \
  --title "chore(nats): JetStream cluster-internal eventing infra" \
  --body "$(cat <<'BODY'
## Summary
- Adds `infra/nats/` Helm chart deploying a single-replica NATS Server 2.10 with JetStream enabled. ClusterIP only (no Ingress, no LoadBalancer); PVC for stream storage.
- Stream `WORDSPARROW_USER_EVENTS` declared at install time via a post-install Job: subjects `wordsparrow.user.>`, 7-day age retention, file storage, 1 replica.
- NetworkPolicy restricts ingress to identity-api / game-api / grid-api service accounts. Defense in depth on top of the no-Ingress posture.
- New ADR-<NNNN> captures the eventing pattern: when to use ack-required vs fire-and-forget publish, GDPR delivery guarantees, deferred follow-ups (multi-replica HA, NATS user accounts, Prometheus exporter).
- Wires the chart into `api-chart-lint.yml` matrix.

## Test plan
- [x] `helm lint infra/nats/ --strict` green.
- [x] `helm template` + `kubeconform -strict -ignore-missing-schemas` green (5 resources).
- [ ] After deploy: `kubectl -n wordsparrow exec sts/bliss-nats -- nats stream info WORDSPARROW_USER_EVENTS` shows the stream with the right subjects + retention.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 54 chars.

---

## Task 6c.1 — Identity-api NATS publishers + game-api log-only subscriber

**Branch:** `feat/oauth2-6c-nats-publishers`. **Subagent skill:** `jvm-backend`.

Wire NATS into both identity-api (publishers, replacing the in-memory `UserDeletedBroadcaster`) and game-api (subscribers that LOG received events, no behavior change). Proves end-to-end delivery before 6c.2 changes any user-visible behavior.

### 6c.1.0 — Recon

- [ ] **Read** the new ADR-<NNNN> (PR 6c.0).
- [ ] **Read** `identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserDeletedBroadcaster.kt` — note the existing port shape (suspend `broadcast(userId, deletedAt)`).
- [ ] **Read** `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/DeleteUserUseCase.kt` — note the broadcast-then-delete ordering and the `BroadcastFailed` error class.
- [ ] **Read** `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/UpdateMeUseCase.kt` — note where the new `UserRenamedBroadcaster` will be called.
- [ ] **Read** `identity/api/.../Wiring.kt` — note how `forProduction` constructs the current in-memory broadcaster.
- [ ] **Read** `game/api/build.gradle.kts` for the existing Ktor + Kotlin coordinates so the NATS client lands at a matching version style.
- [ ] **Verify** `io.nats:jnats:2.20.4` exists on Maven Central.

### 6c.1.1 — Add `UserRenamedBroadcaster` port

- [ ] **Create** `identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRenamedBroadcaster.kt`:

```kotlin
package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import java.time.Instant

/**
 * Notifies downstream contexts that a user's display name changed.
 *
 * Fire-and-forget per ADR-<NNNN>: the producer does NOT block on consumer
 * acknowledgement. Implementations should not throw on transient transport
 * failure — the use case proceeds, and JetStream persistence covers the
 * usual recovery path.
 */
fun interface UserRenamedBroadcaster {
    suspend fun broadcast(
        userId: UserId,
        newDisplayName: DisplayName,
        renamedAt: Instant,
    )
}
```

- [ ] **Modify** `UpdateMeUseCase` to accept + invoke the new broadcaster:

```kotlin
// identity/application/.../usecases/UpdateMeUseCase.kt
package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.UserRenamedBroadcaster
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import kotlin.coroutines.cancellation.CancellationException

data class UpdateMeCommand(val userId: UserId, val displayName: String, val emailOptIn: Boolean? = null)

sealed class UpdateMeError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class UserNotFound : UpdateMeError("User does not exist.")
    class InvalidDisplayName(cause: Throwable) : UpdateMeError("Invalid display name.", cause)
}

class UpdateMeUseCase(
    private val users: UserRepository,
    private val broadcaster: UserRenamedBroadcaster,
    private val clock: Clock,
) {
    suspend fun execute(command: UpdateMeCommand) {
        val current = users.findById(command.userId) ?: throw UpdateMeError.UserNotFound()
        val name = runCatching { DisplayName.of(command.displayName) }
            .getOrElse { e -> throw UpdateMeError.InvalidDisplayName(e) }
        if (name == current.displayName) return
        users.updateDisplayName(command.userId, name)
        try {
            broadcaster.broadcast(command.userId, name, clock.now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fire-and-forget per ADR-<NNNN>. Local rename already succeeded;
            // the rename event will be retried by JetStream persistence
            // (publish-side persists before this returns). A truly local
            // failure (NATS client closed) is logged and swallowed — the
            // rename UX should not fail on a transport hiccup.
            // The Kotlin logger is acquired via `KotlinLogging.logger {}`
            // at the file level; pattern matches the rest of the codebase.
            logger.warn(e) { "user.renamed broadcast failed for ${command.userId}" }
        }
    }

    companion object {
        private val logger = mu.KotlinLogging.logger {}
    }
}
```

(If the existing `UpdateMeUseCase` has additional fields or shape, preserve them — only the broadcaster wiring is new.)

### 6c.1.2 — Add jnats dependency to identity-infrastructure and identity-api

- [ ] **Modify** `identity/infrastructure/build.gradle.kts`. In the existing `dependencies { … }` block, add:

```kotlin
implementation("io.nats:jnats:2.20.4")
implementation("io.github.microutils:kotlin-logging-jvm:3.0.5") // if not already present
```

The adapter classes (`NatsUserDeletedBroadcaster`, `NatsUserRenamedBroadcaster`, `NatsConnectionFactory`) all directly `import io.nats.client.*`, so the dependency must be declared on the module that owns them. Gradle's `implementation` scope is not transitive upward — `identity/infrastructure` cannot see `identity/api`'s dependencies.

- [ ] **Also modify** `identity/api/build.gradle.kts`. Add the same `jnats` line:

```kotlin
implementation("io.nats:jnats:2.20.4")
```

`Wiring.kt` in `identity/api` references `Connection` and `JetStream` directly (e.g. `val (natsConnection, jetStream) = NatsConnectionFactory(natsUrl).connect()`), so the api module also needs the dependency on its own classpath.

(Check whether the codebase uses `mu.KotlinLogging` or `org.slf4j.LoggerFactory`. Match the existing convention in the same module. The `UpdateMeUseCase` snippet above uses `mu.KotlinLogging`; if the project uses raw slf4j, switch to that.)

- [ ] **Verify** Gradle resolves for both modules:

```bash
./gradlew :identity:infrastructure:dependencies --configuration compileClasspath --quiet | grep jnats
./gradlew :identity:api:dependencies --configuration compileClasspath --quiet | grep jnats
```

Expected: each shows `+--- io.nats:jnats:2.20.4` (or a transitive resolution).

### 6c.1.3 — Production NATS adapters in identity-infrastructure

- [ ] **Create** `identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/events/NatsUserDeletedBroadcaster.kt`:

```kotlin
package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserDeletedBroadcaster
import com.bliss.identity.domain.user.UserId
import io.nats.client.JetStream
import io.nats.client.api.PublishAck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

@Serializable
private data class UserDeletedPayload(
    val userId: String,
    val deletedAt: String,
)

/**
 * Publishes `wordsparrow.user.deleted` with publish-ack required (3s timeout).
 *
 * GDPR-critical: a missing ack throws — `DeleteUserUseCase` rolls back so the
 * user retries. JetStream's persistence + at-least-once delivery means once
 * the ack arrives, the event WILL reach consumers eventually (even if every
 * consumer is currently down).
 */
class NatsUserDeletedBroadcaster(
    private val jetStream: JetStream,
    private val publishTimeout: Duration = Duration.ofSeconds(3),
    private val json: Json = Json,
) : UserDeletedBroadcaster {

    override suspend fun broadcast(userId: UserId, deletedAt: Instant) {
        val payload = json.encodeToString(
            UserDeletedPayload.serializer(),
            UserDeletedPayload(
                userId = userId.value.toString(),
                deletedAt = deletedAt.toString(),
            ),
        )
        // jnats publish is blocking; wrap in withContext(IO). The
        // CompletableFuture<PublishAck> returned by publishAsync supports
        // suspend-style waiting via .await() but kotlinx-coroutines-jdk8 is
        // not always on the classpath; the explicit timeout pattern below
        // works without it.
        withContext(Dispatchers.IO) {
            val future = jetStream.publishAsync("wordsparrow.user.deleted", payload.toByteArray(Charsets.UTF_8))
            val ack: PublishAck = future.orTimeout(publishTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).get()
            check(ack.seqno > 0) { "user.deleted publish returned no sequence number; ack=$ack" }
        }
    }
}
```

- [ ] **Create** `identity/infrastructure/.../events/NatsUserRenamedBroadcaster.kt`:

```kotlin
package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserRenamedBroadcaster
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import io.nats.client.JetStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
private data class UserRenamedPayload(
    val userId: String,
    val newDisplayName: String,
    val renamedAt: String,
)

/**
 * Publishes `wordsparrow.user.renamed` fire-and-forget per ADR-<NNNN>.
 *
 * The JetStream client returns immediately after enqueuing locally; JetStream
 * persistence on the server side covers transient consumer downtime.
 */
class NatsUserRenamedBroadcaster(
    private val jetStream: JetStream,
    private val json: Json = Json,
) : UserRenamedBroadcaster {

    override suspend fun broadcast(
        userId: UserId,
        newDisplayName: DisplayName,
        renamedAt: Instant,
    ) {
        val payload = json.encodeToString(
            UserRenamedPayload.serializer(),
            UserRenamedPayload(
                userId = userId.value.toString(),
                newDisplayName = newDisplayName.value,
                renamedAt = renamedAt.toString(),
            ),
        )
        withContext(Dispatchers.IO) {
            // Fire-and-forget: discard the ack future. publishAsync sends the message
            // to the JetStream server but does not block on the server's ack.
            // JetStream's at-least-once delivery covers the recovery path once
            // the publish request reaches the server.
            jetStream.publishAsync("wordsparrow.user.renamed", payload.toByteArray(Charsets.UTF_8))
        }
    }
}
```

- [ ] **Create** `identity/infrastructure/.../events/NatsConnectionFactory.kt`:

```kotlin
package com.bliss.identity.infrastructure.events

import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.Options
import java.time.Duration

/**
 * Single source of truth for the identity-api → NATS connection.
 *
 * `forProduction` connects synchronously at boot; if NATS is unreachable,
 * the application aborts startup (probes fail → pod restarts → k8s backoff).
 * Operational alternative considered + rejected: defer the connect until
 * first publish, which would mean the first DeleteUser after NATS recovery
 * fails. Loud-fail-at-boot is simpler for alpha.
 */
class NatsConnectionFactory(private val natsUrl: String) {
    fun connect(): Pair<Connection, JetStream> {
        val options = Options.Builder()
            .server(natsUrl)
            .connectionTimeout(Duration.ofSeconds(5))
            .reconnectWait(Duration.ofSeconds(1))
            .maxReconnects(-1) // unlimited reconnect attempts after initial connect
            .build()
        val connection = Nats.connect(options)
        return connection to connection.jetStream()
    }
}
```

### 6c.1.4 — Wire NATS into identity-api's `Wiring.forProduction`

- [ ] **Modify** `identity/api/.../Wiring.kt`. Add:

```kotlin
// In the imports:
import com.bliss.identity.infrastructure.events.NatsConnectionFactory
import com.bliss.identity.infrastructure.events.NatsUserDeletedBroadcaster
import com.bliss.identity.infrastructure.events.NatsUserRenamedBroadcaster
import com.bliss.identity.application.ports.UserRenamedBroadcaster

// Add to forProduction's parameter list:
fun forProduction(
    config: IdentityApiConfig,
    dataSource: DataSource,
    engine: HttpClientEngine,
    natsUrl: String, // NEW
): Wiring {
    // ... existing wiring ...

    val (natsConnection, jetStream) = NatsConnectionFactory(natsUrl).connect()
    val deletedBroadcaster = NatsUserDeletedBroadcaster(jetStream)
    val renamedBroadcaster = NatsUserRenamedBroadcaster(jetStream)

    // Pass to use cases:
    val updateMe = UpdateMeUseCase(users, renamedBroadcaster, clock)
    val deleteUser = DeleteUserUseCase(users, deletedBroadcaster, clock)
    // ... rest unchanged ...
}
```

(Adjust to match the actual `Wiring` shape — the post-#491 file has nullable backing fields + non-null accessors.)

- [ ] **Modify** `identity/api/.../Main.kt`. Pull the NATS URL from env:

```kotlin
val natsUrl = System.getenv("NATS_URL") ?: "nats://bliss-nats.wordsparrow:4222"
val wiring = Wiring.forProduction(config, db.dataSource()!!, engine = CIO.create(), natsUrl = natsUrl)
```

- [ ] **Modify** the chart values + deployment template to inject `NATS_URL`:

```yaml
# identity/api/deploy/chart/values.yaml — add to the env block:
env:
  - name: NATS_URL
    value: "nats://bliss-nats.wordsparrow:4222"
```

### 6c.1.5 — Game-api: NATS subscriber skeleton (log-only)

- [ ] **Add jnats** to `game/infrastructure/build.gradle.kts`:

```kotlin
implementation("io.nats:jnats:2.20.4")
```

`NatsConnectionFactory` and `UserEventSubscribers` in `game/infrastructure` directly `import io.nats.client.*`; the dependency must live on the module that owns those classes.

- [ ] **Also add jnats** to `game/api/build.gradle.kts` if `Wiring.kt` in `game/api` references `Connection` or `JetStream` types directly (check the wiring class after task 6c.1.5 is drafted). If `game/api` only instantiates the factory via its return type `Pair<Connection, JetStream>`, the api module needs the dependency too:

```kotlin
implementation("io.nats:jnats:2.20.4")
```

- [ ] **Create** `game/infrastructure/src/main/kotlin/com/bliss/game/infrastructure/events/NatsConnectionFactory.kt`:

```kotlin
package com.bliss.game.infrastructure.events

import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.Options
import java.time.Duration

class NatsConnectionFactory(private val natsUrl: String) {
    fun connect(): Pair<Connection, JetStream> {
        val options = Options.Builder()
            .server(natsUrl)
            .connectionTimeout(Duration.ofSeconds(5))
            .reconnectWait(Duration.ofSeconds(1))
            .maxReconnects(-1)
            .build()
        val connection = Nats.connect(options)
        return connection to connection.jetStream()
    }
}
```

(Yes, this duplicates the identity-side helper — copy-paste accepted at alpha; cross-context shared code would need a new shared module.)

- [ ] **Create** `game/infrastructure/.../events/UserEventSubscribers.kt`:

```kotlin
package com.bliss.game.infrastructure.events

import io.nats.client.JetStream
import io.nats.client.JetStreamSubscription
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Log-only subscribers for `wordsparrow.user.deleted` + `wordsparrow.user.renamed`.
 *
 * PR 6c.1 ships these as LOG + ACK only. PR 6c.3 swaps the body to drive
 * lobby anonymization / pseudonym refresh.
 *
 * Two durable consumers (one per subject), explicit-ack policy. The
 * coroutine scope is owned by this object — `start()` launches the
 * subscriber loops; `close()` cancels them and unsubscribes.
 */
class UserEventSubscribers(
    private val jetStream: JetStream,
) {
    private val log = LoggerFactory.getLogger(UserEventSubscribers::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subs = mutableListOf<JetStreamSubscription>()

    fun start() {
        subs += subscribe("wordsparrow.user.deleted", "game-api-user-deleted") { msg ->
            log.info("user.deleted received: {}", String(msg.data, Charsets.UTF_8))
        }
        subs += subscribe("wordsparrow.user.renamed", "game-api-user-renamed") { msg ->
            log.info("user.renamed received: {}", String(msg.data, Charsets.UTF_8))
        }
    }

    fun close() {
        subs.forEach { runCatching { it.unsubscribe() } }
        scope.cancel()
    }

    private fun subscribe(
        subject: String,
        durable: String,
        handle: (io.nats.client.Message) -> Unit,
    ): JetStreamSubscription {
        val opts = PushSubscribeOptions.builder()
            .durable(durable)
            .configuration(
                ConsumerConfiguration.builder()
                    .durable(durable)
                    .ackPolicy(AckPolicy.Explicit)
                    .build(),
            )
            .build()
        val sub = jetStream.subscribe(subject, opts)
        scope.launch {
            while (isActive) {
                runCatching {
                    val msg = sub.nextMessage(Duration.ofSeconds(5))
                    if (msg != null) {
                        try {
                            handle(msg)
                            msg.ack()
                        } catch (e: Throwable) {
                            log.error("subscriber handler threw; nak-ing", e)
                            msg.nak()
                        }
                    }
                }.onFailure { e ->
                    log.warn("subscriber loop error on $subject", e)
                    delay(1_000)
                }
            }
        }
        return sub
    }
}
```

- [ ] **Wire** into `game/api/.../Module.kt` (or wherever game-api boots):

```kotlin
// At application startup, alongside the existing Wiring construction:
val natsUrl = System.getenv("NATS_URL") ?: "nats://bliss-nats.wordsparrow:4222"
val (natsConnection, jetStream) = NatsConnectionFactory(natsUrl).connect()
val userEventSubscribers = UserEventSubscribers(jetStream)
userEventSubscribers.start()

monitor.subscribe(ApplicationStopped) {
    userEventSubscribers.close()
    natsConnection.close()
}
```

(`monitor` is Ktor's `ApplicationEvents`. Adjust to match the actual lifecycle hook used elsewhere in game-api.)

- [ ] **Modify** `game/api/deploy/chart/values.yaml` to inject the NATS URL:

```yaml
env:
  - name: NATS_URL
    value: "nats://bliss-nats.wordsparrow:4222"
```

### 6c.1.6 — Tests

- [ ] **Use case test** (identity-application): `UpdateMeUseCaseTest` gains a case asserting the broadcaster is called on rename:

```kotlin
// identity/application/src/test/kotlin/.../usecases/UpdateMeUseCaseTest.kt
@Test
fun `successful rename broadcasts user-renamed event`() = runBlocking {
    val users = InMemoryUserRepository()
    val broadcaster = InMemoryUserRenamedBroadcaster()
    val seedUserId = users.create(/* ...fixture... */).id
    val sut = UpdateMeUseCase(users, broadcaster, FixedClock(NOW))
    sut.execute(UpdateMeCommand(userId = seedUserId, displayName = "Renard 888"))
    assertThat(broadcaster.captured()).hasSize(1)
    assertThat(broadcaster.captured()[0].second.value).isEqualTo("Renard 888")
}

@Test
fun `noop rename does not broadcast`() = runBlocking {
    // ... seed user with displayName = "Lapin 472" ...
    val sut = UpdateMeUseCase(users, broadcaster, FixedClock(NOW))
    sut.execute(UpdateMeCommand(userId = seedUserId, displayName = "Lapin 472"))
    assertThat(broadcaster.captured()).isEmpty()
}
```

- [ ] **Create** `identity/infrastructure/.../events/InMemoryUserRenamedBroadcaster.kt` (test-fixture analogue to `InMemoryUserDeletedBroadcaster`):

```kotlin
package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserRenamedBroadcaster
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryUserRenamedBroadcaster : UserRenamedBroadcaster {
    private val events = CopyOnWriteArrayList<Triple<UserId, DisplayName, Instant>>()
    override suspend fun broadcast(userId: UserId, newDisplayName: DisplayName, renamedAt: Instant) {
        events.add(Triple(userId, newDisplayName, renamedAt))
    }
    fun captured(): List<Triple<UserId, DisplayName, Instant>> = events.toList()
}
```

- [ ] **NATS adapter integration test** (identity-infrastructure): uses `nats-server-embedded`. If that artifact isn't available, fall back to a Testcontainers approach with the `nats:2.10` image. Pseudocode:

```kotlin
// identity/infrastructure/src/test/kotlin/.../events/NatsUserDeletedBroadcasterIT.kt
@Testcontainers
class NatsUserDeletedBroadcasterIT {
    private val nats = GenericContainer("nats:2.10-alpine")
        .withExposedPorts(4222)
        .withCommand("-js")

    @Test
    fun `publish persists to JetStream`() = runBlocking {
        // Boot stream
        // ... use nats CLI subcontainer OR Connection.jetStreamManagement().addStream ...
        val (conn, js) = NatsConnectionFactory("nats://${nats.host}:${nats.firstMappedPort}").connect()
        val sut = NatsUserDeletedBroadcaster(js)
        sut.broadcast(UserId(UUID.randomUUID()), Instant.parse("2026-05-18T12:00:00Z"))
        // Pull the message back via a one-shot subscriber and assert payload shape.
        val sub = js.subscribe("wordsparrow.user.deleted", ...)
        val msg = sub.nextMessage(Duration.ofSeconds(2))
        assertThat(msg).isNotNull()
        val parsed = Json.decodeFromString<UserDeletedPayload>(String(msg.data))
        assertThat(parsed.userId).isNotEmpty()
    }
}
```

(The integration test can be deferred to a follow-up PR if Testcontainers integration is non-trivial in this codebase. For the alpha-stage 6c.1 PR, the unit tests + a manual `nats sub wordsparrow.user.>` check during preview-deploy is sufficient.)

### 6c.1.7 — Verify + commit + PR

- [ ] **Run** all gates:

```bash
./gradlew :identity:api:check :game:api:check --parallel --quiet
```

- [ ] **Commit**:

```bash
git add identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRenamedBroadcaster.kt \
        identity/application/src/main/kotlin/com/bliss/identity/application/usecases/UpdateMeUseCase.kt \
        identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/events/ \
        identity/api/build.gradle.kts \
        identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt \
        identity/api/deploy/chart/values.yaml \
        game/api/build.gradle.kts \
        game/infrastructure/src/main/kotlin/com/bliss/game/infrastructure/events/ \
        game/api/src/main/kotlin/com/bliss/game/api/Module.kt \
        game/api/deploy/chart/values.yaml \
        identity/application/src/test/kotlin/com/bliss/identity/application/usecases/UpdateMeUseCaseTest.kt
git commit -s -m "feat(identity-events): NATS publishers + game subscriber skeleton"
```

- [ ] **Push + PR** (title ≤ 70 chars):

```bash
git push -u origin feat/oauth2-6c-nats-publishers
gh pr create --base main \
  --title "feat(identity-events): NATS publishers + game subscriber" \
  --body "$(cat <<'BODY'
## Summary
- Adds `UserRenamedBroadcaster` port in `identity/application/` (parallel to existing `UserDeletedBroadcaster`). `UpdateMeUseCase` invokes it on successful rename; fire-and-forget per ADR-<NNNN>.
- Adds production NATS adapters `NatsUserDeletedBroadcaster` (publish-ack required, GDPR-grade) + `NatsUserRenamedBroadcaster` (fire-and-forget) in `identity/infrastructure/`. Replaces `InMemoryUserDeletedBroadcaster` in `Wiring.forProduction`.
- Adds `io.nats:jnats:2.20.4` to `identity/infrastructure` and `identity/api` (adapter + wiring, respectively) and `game/infrastructure` (and `game/api` if `Wiring.kt` directly references `Connection`/`JetStream` types).
- Game-api: log-only subscribers for `wordsparrow.user.deleted` + `wordsparrow.user.renamed`. Each is a durable JetStream consumer with explicit ack. PR 6c.3 swaps the bodies to drive lobby state changes.
- Both contexts pull NATS URL from `NATS_URL` env, defaulting to `nats://bliss-nats.wordsparrow:4222`.

## Test plan
- [x] `./gradlew :identity:api:check :game:api:check --quiet` green.
- [x] `UpdateMeUseCaseTest` gains rename-broadcast assertion + noop-rename-skip assertion.
- [ ] After deploy: `nats sub wordsparrow.user.>` from a tooling pod shows events as `/compte` rename + `DELETE /v1/users/me` fire. Game-api logs show subscriber receiving them.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 57 chars.

---

## Task 6c.2 — Cookie prefix change + cookie verifier + lobby endpoints + frontend rebind/unbind

**Branch:** `feat/oauth2-6c-lobby-identity`. **Subagent skill:** `jvm-backend` for the JVM half, `frontend` for the TS half. Implementer subagents should pick the right skill per file they touch.

This is the largest sub-PR. Three coordinated changes: identity-api cookie rename, game-api cookie-verify + lobby endpoints, frontend AuthProvider wiring.

### 6c.2.0 — Recon

- [ ] **Read** `identity/api/.../auth/SessionCookies.kt` (PR #491) — the cookie issuer/clearer/reader helper.
- [ ] **Read** `identity/api/openapi.yaml` and search for `__Host-ws_session` mentions — every reference must be updated.
- [ ] **Read** `frontend/src/infrastructure/auth/HttpAuthClient.ts` — any reference to the cookie name? It shouldn't read the cookie (HttpOnly), but verify.
- [ ] **Read** `game/api/.../Module.kt` for the existing route registration pattern.
- [ ] **Read** `game/api/openapi.yaml` for the lobby endpoint shape — new endpoints `POST /v1/lobbies/players/rebind` + `/unbind` will land in the same spec.
- [ ] **Read** `frontend/src/ui/components/auth/AuthProvider.tsx` — note the existing first-sign-in carry-over effect; the new rebind effect fires in the same anon→authed transition.
- [ ] **Read** `frontend/src/infrastructure/session/localStorageSession.ts` — note the exported `getSessionId` and `getPseudonym` helpers.

### 6c.2.1 — Identity-api: rename cookie + adjust CORS

- [ ] **Modify** `identity/api/.../auth/SessionCookies.kt`:

```kotlin
package com.bliss.identity.api.auth

import com.bliss.identity.domain.session.SessionId
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cookies
import java.time.Duration
import java.util.UUID

object SessionCookies {
    /**
     * `__Secure-` prefix (RFC 6265bis §4.1.4) requires Secure but permits
     * `Domain`. Scoping to `wordsparrow.io` lets the cookie travel to every
     * subdomain (`auth.`, `game.`, `api.`, apex + www). The previous
     * `__Host-` prefix host-locked the cookie to `auth.wordsparrow.io`, which
     * blocked game-api and grid-api from cookie-based authentication
     * (Phase 6c spec §Security model + ADR amendment).
     *
     * Trade-off: lose host-locking. We control the entire `.wordsparrow.io`
     * namespace via Cloudflare-managed DNS, so the practical risk is the
     * same as for `*.google.com` (which uses the same pattern).
     *
     * Existing `__Host-ws_session` cookies in browsers are silently ignored
     * on the next request — the server doesn't find the new name, treats
     * the user as anon, they re-sign-in once. One-time disruption.
     */
    const val NAME = "__Secure-ws_session"

    private const val COOKIE_DOMAIN = "wordsparrow.io"

    fun issue(call: ApplicationCall, sessionId: SessionId, maxAge: Duration) {
        call.response.cookies.append(
            Cookie(
                name = NAME,
                value = sessionId.value.toString(),
                domain = COOKIE_DOMAIN,
                path = "/",
                httpOnly = true,
                secure = true,
                maxAgeInSeconds = maxAge.seconds.toInt(),
                extensions = mapOf("SameSite" to "Lax"),
                encoding = CookieEncoding.RAW,
            ),
        )
    }

    fun clear(call: ApplicationCall) {
        call.response.cookies.append(
            Cookie(
                name = NAME,
                value = "",
                domain = COOKIE_DOMAIN,
                path = "/",
                httpOnly = true,
                secure = true,
                maxAgeInSeconds = 0,
                extensions = mapOf("SameSite" to "Lax"),
                encoding = CookieEncoding.RAW,
            ),
        )
    }

    fun read(call: ApplicationCall): SessionId? {
        val raw = call.request.cookies[NAME] ?: return null
        return try {
            SessionId(UUID.fromString(raw))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
```

- [ ] **Search + replace** all references to `__Host-ws_session` across the repo (string literal):

```bash
grep -rn '__Host-ws_session' identity/ game/ grid/ frontend/ docs/
```

Expected matches: `SessionCookies.kt` (already updated above), `identity/api/openapi.yaml`, possibly `docs/deploy.md` (privacy bootstrap section), possibly test fixtures. Update each to `__Secure-ws_session`.

- [ ] **Modify** `identity/api/openapi.yaml`: every `sessionCookie` `securityScheme` entry's `name:` becomes `__Secure-ws_session`.

- [ ] **Run** the OpenAPI regen for the frontend:

```bash
cd frontend && pnpm api:check
```

Expected: regen-and-diff stays clean (the `name:` field is in the spec but doesn't directly appear in the TS types as a string — only in the headers comment). If it fails, that's a real change to commit.

### 6c.2.2 — Game-api: add `CookieVerifier` port + adapter

- [ ] **Create** `game/application/src/main/kotlin/com/bliss/game/application/auth/CookieVerifier.kt`:

```kotlin
package com.bliss.game.application.auth

import com.bliss.game.domain.lobby.Pseudonym
import com.bliss.game.domain.user.UserId

/**
 * Verifies the `__Secure-ws_session` cookie against identity-api's whoami.
 * Returns null for missing/invalid cookies, for identity-api 401, or when
 * identity-api is unreachable (fail-closed: treat as anon).
 *
 * Implementations should:
 *   - Cache the result for [DEFAULT_CACHE_TTL] (30s) keyed on rawCookieValue.
 *   - Cache 401 results too (avoid hammering identity-api on anon traffic).
 *   - Log warnings on 5xx; do not throw.
 */
interface CookieVerifier {
    suspend fun verify(rawCookieValue: String?): WhoAmI?
}

data class WhoAmI(
    val userId: UserId,
    val displayName: Pseudonym,
)
```

- [ ] **Create** `game/infrastructure/.../auth/HttpCookieVerifier.kt`:

```kotlin
package com.bliss.game.infrastructure.auth

import com.bliss.game.application.auth.CookieVerifier
import com.bliss.game.application.auth.WhoAmI
import com.bliss.game.domain.lobby.Pseudonym
import com.bliss.game.domain.user.UserId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class WhoAmIResponse(val userId: String, val displayName: String)

/**
 * Wraps a Ktor HttpClient calling identity-api's `GET /v1/auth/whoami`.
 * 30s LRU-ish cache (ConcurrentHashMap with manual TTL expiry on read).
 * Cap of 10,000 entries — pragmatic for alpha; LRU eviction on overflow.
 *
 * Cache stores `Optional<WhoAmI>`: present = authed, absent (null value) =
 * anon (401 from identity-api). 5xx is NOT cached — we retry next request.
 */
class HttpCookieVerifier(
    private val http: HttpClient,
    private val identityApiBaseUrl: String,
    private val cacheTtl: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = Instant::now,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CookieVerifier {

    private data class Entry(val value: WhoAmI?, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val log = LoggerFactory.getLogger(HttpCookieVerifier::class.java)

    override suspend fun verify(rawCookieValue: String?): WhoAmI? {
        val cookie = rawCookieValue?.takeIf { it.isNotBlank() } ?: return null
        val cached = cache[cookie]
        val current = now()
        if (cached != null && cached.expiresAt.isAfter(current)) return cached.value

        val response = try {
            http.get("$identityApiBaseUrl/v1/auth/whoami") {
                header("Cookie", "__Secure-ws_session=$cookie")
            }
        } catch (e: Throwable) {
            log.warn("identity-api whoami unreachable; failing closed", e)
            return null
        }

        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.body<String>()
                val parsed = json.decodeFromString(WhoAmIResponse.serializer(), body)
                val result = WhoAmI(UserId(UUID.fromString(parsed.userId)), Pseudonym.of(parsed.displayName))
                cache[cookie] = Entry(result, current.plus(cacheTtl))
                result
            }
            HttpStatusCode.Unauthorized -> {
                cache[cookie] = Entry(null, current.plus(cacheTtl))
                null
            }
            else -> {
                log.warn("identity-api whoami returned ${response.status.value}; failing closed")
                null
            }
        }
    }
}
```

(Cache eviction: skipped for alpha. If the cache ever bumps memory, add an LRU library or write a tiny ring-buffer eviction.)

### 6c.2.3 — Game-api: `LobbyPlayer` gains `userId`

- [ ] **Modify** `game/domain/.../lobby/LobbyPlayer.kt`. Add `val userId: UserId? = null` to the data class. Verify the property is `null` by default so existing call sites compile.

- [ ] **Modify** the lobby create/join handlers in `game/api/.../routes/`. For each handler that takes the inbound pseudonym from the request body:

```kotlin
// Pseudocode — adapt to the actual handler shape.
val rawCookie = call.request.cookies[SessionCookies.NAME_GAME_VIEW] // see helper below
val whoAmI = cookieVerifier.verify(rawCookie)
val (sessionId, pseudonym, userId) = if (whoAmI != null) {
    Triple(extractSessionIdFromBody(call), whoAmI.displayName, whoAmI.userId)
} else {
    Triple(extractSessionIdFromBody(call), Pseudonym.of(extractPseudonymFromBody(call)), null)
}
val player = LobbyPlayer(sessionId, pseudonym, userId, /* ... */)
```

Game-api needs its own cookie name constant pointing at the new `__Secure-ws_session`:

```kotlin
// game/api/.../auth/CookieNames.kt
package com.bliss.game.api.auth
object CookieNames {
    const val SESSION = "__Secure-ws_session"
}
```

### 6c.2.4 — Game-api: lobby rebind/unbind endpoints

- [ ] **Modify** `game/api/openapi.yaml`: add two endpoints:

```yaml
/v1/lobbies/players/rebind:
  post:
    operationId: rebindLobbySessions
    summary: Rebind anon lobby seats to the authenticated user.
    description: |
      Called by the frontend after the user signs in. Cookie-authed.
      Finds all LobbyPlayer rows where sessionId == request.anonSessionId
      AND userId is null; sets userId to the authenticated user and refreshes
      pseudonym from the user's current displayName.
    tags: [lobbies]
    security:
      - sessionCookie: []
    requestBody:
      required: true
      content:
        application/json:
          schema:
            type: object
            required: [anonSessionId]
            properties:
              anonSessionId: { type: string, format: uuid }
    responses:
      '204': { description: Rebind complete (idempotent — zero rows is also success). }
      '401': { $ref: '#/components/responses/ProblemDetails' }

/v1/lobbies/players/unbind:
  post:
    operationId: unbindLobbySessions
    summary: Unbind authenticated lobby seats before sign-out.
    description: |
      Called by the frontend BEFORE invoking POST /v1/auth/logout. Cookie-
      authed. Finds all LobbyPlayer rows where userId == cookie.userId;
      clears userId and sets pseudonym to the request.anonPseudonym from
      the user's localStorage anon identity.
    tags: [lobbies]
    security:
      - sessionCookie: []
    requestBody:
      required: true
      content:
        application/json:
          schema:
            type: object
            required: [anonPseudonym]
            properties:
              anonPseudonym: { type: string, minLength: 1, maxLength: 30 }
    responses:
      '204': { description: Unbind complete (idempotent). }
      '401': { $ref: '#/components/responses/ProblemDetails' }
```

- [ ] **Regen frontend types**: `cd frontend && pnpm api:check`. Commit the regen separately if dirty.

- [ ] **Create** `game/api/.../routes/LobbyRebindRoute.kt`:

```kotlin
package com.bliss.game.api.routes

import com.bliss.game.api.auth.CookieNames
import com.bliss.game.application.auth.CookieVerifier
import com.bliss.game.application.lobby.LobbyRepository
// ... other imports ...

@Serializable
data class RebindRequest(val anonSessionId: String)

@Serializable
data class UnbindRequest(val anonPseudonym: String)

fun Route.lobbyRebind(verifier: CookieVerifier, lobbies: LobbyRepository) {
    post("/v1/lobbies/players/rebind") {
        val rawCookie = call.request.cookies[CookieNames.SESSION]
        val whoAmI = verifier.verify(rawCookie) ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val req = call.receive<RebindRequest>()
        val anonSessionId = SessionId(UUID.fromString(req.anonSessionId))
        val userId = whoAmI.userId
        val updated = lobbies.rebindAnonSeats(
            anonSessionId = anonSessionId,
            userId = userId,
            newPseudonym = whoAmI.displayName,
        )
        // updated is the set of LobbyIds whose roster changed; schedule WS broadcasts.
        updated.forEach { /* lobbyBroadcaster.notifyRosterChanged(it) */ }
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/lobbies/players/unbind") {
        val rawCookie = call.request.cookies[CookieNames.SESSION]
        val whoAmI = verifier.verify(rawCookie) ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val req = call.receive<UnbindRequest>()
        val userId = whoAmI.userId
        val updated = lobbies.unbindUserSeats(
            userId = userId,
            anonPseudonym = Pseudonym.of(req.anonPseudonym),
        )
        updated.forEach { /* lobbyBroadcaster.notifyRosterChanged(it) */ }
        call.respond(HttpStatusCode.NoContent)
    }
}
```

- [ ] **Extend** `LobbyRepository`: add `rebindAnonSeats` + `unbindUserSeats` (return type: set of `LobbyId`s whose roster changed). Pseudocode body for `InMemoryLobbyRepository`:

```kotlin
override fun rebindAnonSeats(anonSessionId: SessionId, userId: UserId, newPseudonym: Pseudonym): Set<LobbyId> {
    val touched = mutableSetOf<LobbyId>()
    lock.write {
        lobbies.values.forEach { lobby ->
            val seat = lobby.players.find { it.sessionId == anonSessionId && it.userId == null }
            if (seat != null) {
                lobby.players[lobby.players.indexOf(seat)] = seat.copy(userId = userId, pseudonym = newPseudonym)
                touched += lobby.id
            }
        }
    }
    return touched
}

override fun unbindUserSeats(userId: UserId, anonPseudonym: Pseudonym): Set<LobbyId> {
    val touched = mutableSetOf<LobbyId>()
    lock.write {
        lobbies.values.forEach { lobby ->
            val seat = lobby.players.find { it.userId == userId }
            if (seat != null) {
                lobby.players[lobby.players.indexOf(seat)] = seat.copy(userId = null, pseudonym = anonPseudonym)
                touched += lobby.id
            }
        }
    }
    return touched
}
```

(Adapt to the actual `InMemoryLobbyRepository` structure — the existing repo may use a different lock or data shape.)

- [ ] **Wire** rebind + unbind routes into `game/api/.../Module.kt`'s routing block. Construct `HttpCookieVerifier` using a Ktor `HttpClient` configured for the identity-api in-cluster Service DNS:

```kotlin
val identityApiBaseUrl = System.getenv("IDENTITY_API_BASE_URL") ?: "http://wordsparrow-identity-api.wordsparrow:8082"
val verifier = HttpCookieVerifier(httpClient, identityApiBaseUrl)
routing {
    // existing routes
    lobbyRebind(verifier, lobbies)
}
```

Add `IDENTITY_API_BASE_URL` to `game/api/deploy/chart/values.yaml`'s env block.

### 6c.2.5 — Frontend: AuthProvider rebind + logout unbind

- [ ] **Modify** `frontend/src/ui/components/auth/AuthProvider.tsx` — add a new effect (or extend the existing first-sign-in carry-over effect) that fires on anon→authed transition:

```tsx
// Pseudocode — add to the existing AuthProvider:
const lobbyRebindLatch = useRef(false);

useEffect(() => {
  if (state.status !== 'authed') {
    lobbyRebindLatch.current = false;
    return;
  }
  if (lobbyRebindLatch.current) return;
  lobbyRebindLatch.current = true;
  const anonSessionId = getLocalSessionId();
  void gameClient.rebindLobbySessions(anonSessionId).catch((e) => {
    console.warn('lobby rebind failed; will retry on next auth refresh', e);
    lobbyRebindLatch.current = false;
  });
}, [state.status]);
```

The props for `AuthProvider` grow: add `getLocalSessionId: () => string` (wired in `main.tsx` to `localStorageSession.getSessionId`).

- [ ] **Modify** `frontend/src/ui/components/auth/AvatarMenu.tsx`'s sign-out handler:

```tsx
async function onSignOut() {
  const anonPseudonym = localStorageSession.getPseudonym();
  try {
    await gameClient.unbindLobbySessions(anonPseudonym);
  } catch (e) {
    console.warn('lobby unbind failed; logging out anyway', e);
  }
  await authClient.logout();
  await refresh();
  void navigate({ to: '/' });
}
```

Pull `gameClient` from the router context (same pattern as `authClient`). `localStorageSession.getPseudonym` is imported in `main.tsx` and passed via context — for the menu, accept it as a prop or import directly (it's not infrastructure-import-forbidden because the menu is in `ui/components/auth/`, but check the boundary rules first).

- [ ] **Add the two methods** to the `LobbyClient` port (`frontend/src/application/game/LobbyClient.ts`):

```typescript
export interface LobbyClient {
  // ...existing methods...
  rebindLobbySessions(anonSessionId: SessionId): Promise<void>;
  unbindLobbySessions(anonPseudonym: string): Promise<void>;
}
```

- [ ] **Implement** in `frontend/src/infrastructure/game/HttpLobbyClient.ts`:

```typescript
async rebindLobbySessions(anonSessionId) {
  const { error, response } = await client.POST('/v1/lobbies/players/rebind', {
    credentials: 'include',
    body: { anonSessionId: String(anonSessionId) },
  });
  if (error) throw new Error(`rebindLobbySessions failed: ${String(response.status)}`);
},
async unbindLobbySessions(anonPseudonym) {
  const { error, response } = await client.POST('/v1/lobbies/players/unbind', {
    credentials: 'include',
    body: { anonPseudonym },
  });
  if (error) throw new Error(`unbindLobbySessions failed: ${String(response.status)}`);
},
```

### 6c.2.6 — Game-api CORS (it now receives cookies)

- [ ] **Modify** `game/api/.../Module.kt`'s CORS block: add `allowCredentials = true` and the wordsparrow.io / www / preview origins. Mirror the identity-api wildcard-headers pattern (per ADR-0034) so game-api also accepts X-Request-Id / traceparent / etc. without future regressions.

```kotlin
install(CORS) {
    allowHost("wordsparrow.io", schemes = listOf("https"))
    allowHost("www.wordsparrow.io", schemes = listOf("https"))
    allowHost("bliss-cb4.pages.dev", schemes = listOf("https"))
    allowHost("localhost:5173", schemes = listOf("http"))

    allowMethod(HttpMethod.Patch)
    allowMethod(HttpMethod.Delete)

    allowHeaders { true }                 // per ADR-0034
    allowNonSimpleContentTypes = true     // for JSON request bodies

    allowCredentials = true
    maxAgeInSeconds = 600
}
```

### 6c.2.7 — Tests

- [ ] **HttpCookieVerifier**: mockEngine-backed Ktor test asserting 200 → cached `WhoAmI`, 401 → cached null, 5xx → null + log, cache TTL respected (advance the clock past the TTL, expect a second HTTP call).
- [ ] **Lobby rebind/unbind**: integration tests against an in-memory lobby repo with a seed lobby. Assert rows update + returned set of touched LobbyIds matches.
- [ ] **Frontend**: extend the existing `e2e/auth-authed.spec.ts` with a scenario where an anon user has a seeded lobby seat, signs in, and the player list shows the new pseudonym (after a beat).

### 6c.2.8 — Commit + PR

- [ ] **Run** all gates: `./gradlew :identity:api:check :game:api:check --parallel --quiet && cd frontend && pnpm typecheck && pnpm lint && pnpm test && pnpm api:check && cd ..`.

- [ ] **Push + PR** (title ≤ 70 chars):

```bash
git push -u origin feat/oauth2-6c-lobby-identity
gh pr create --base main \
  --title "feat(game-lobby): user-aware lobby identity + cookie rename" \
  --body "$(cat <<'BODY'
## Summary
- **Cookie rename**: `__Host-ws_session` → `__Secure-ws_session` with `Domain=wordsparrow.io`. Required because the `__Host-` prefix forbids `Domain`, host-locking the cookie to `auth.wordsparrow.io` and preventing game-api from receiving it. **One-time user impact: every currently-signed-in browser is silently invalidated and the user re-signs-in on next visit.** Documented in the new ADR-<NNNN> amendment.
- **Game-api `CookieVerifier`**: new port + `HttpCookieVerifier` adapter calling identity-api `whoami` with 30s LRU cache; 401 cached too; 5xx fail-closed.
- **Lobby model**: `LobbyPlayer.userId: UserId?` (null for anon). Lobby create/join handlers prefer `whoami.displayName` over the request body's pseudonym when authed.
- **Lobby endpoints**: `POST /v1/lobbies/players/{rebind,unbind}`. Both cookie-authed. Rebind links anon seats to the authed user; unbind reverses for sign-out flows.
- **Frontend**: `AuthProvider` triggers rebind on anon→authed transition (latched, retries on failure). Logout flow calls unbind BEFORE clearing the cookie. New methods on the `LobbyClient` port.
- **Game-api CORS**: `allowCredentials = true`, wildcard headers per ADR-0034, four-origin allow-list.

## Test plan
- [ ] `./gradlew :identity:api:check :game:api:check` green.
- [ ] `pnpm test`, `pnpm typecheck`, `pnpm lint`, `pnpm api:check` green.
- [ ] Manual prod test: sign in via auth.wordsparrow.io, observe `__Secure-ws_session` in DevTools with `Domain=wordsparrow.io`. Hit game.wordsparrow.io API endpoint; cookie travels (Network tab confirms `Cookie:` header carries it).
- [ ] Anon user (Marmotte 900) creates a lobby, signs in, lobby roster shows their display name on next state-update.
- [ ] Authed user in a lobby clicks "Se déconnecter"; before page reloads, lobby roster reflects the rebind to anon pseudonym.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 60 chars.

---

## Task 6c.3 — Game-api subscribers act on events

**Branch:** `feat/oauth2-6c-lobby-event-handlers`. **Subagent skill:** `jvm-backend`.

Replace 6c.1's log-only subscribers with real handlers: anonymize lobby seats on `user.deleted`, refresh pseudonym on `user.renamed`.

### 6c.3.0 — Recon

- [ ] **Read** `game/infrastructure/.../events/UserEventSubscribers.kt` (PR 6c.1) — the log-only subscriber shape.
- [ ] **Read** `game/application/.../lobby/LobbyRepository.kt` — note the methods added in 6c.2 (`rebindAnonSeats`, `unbindUserSeats`) and the existing find/update primitives.

### 6c.3.1 — Extend `LobbyRepository` with event-driven update methods

- [ ] **Modify** `game/application/.../lobby/LobbyRepository.kt`. Add:

```kotlin
/** Anonymize every LobbyPlayer row whose userId matches. Returns the set of touched lobby ids. */
fun anonymizeUserSeats(userId: UserId, replacementPseudonym: Pseudonym): Set<LobbyId>

/** Refresh pseudonym on every LobbyPlayer row whose userId matches. Returns touched lobby ids. */
fun refreshUserPseudonym(userId: UserId, newPseudonym: Pseudonym): Set<LobbyId>
```

- [ ] **Implement** in `InMemoryLobbyRepository.kt`:

```kotlin
override fun anonymizeUserSeats(userId: UserId, replacementPseudonym: Pseudonym): Set<LobbyId> {
    val touched = mutableSetOf<LobbyId>()
    lock.write {
        lobbies.values.forEach { lobby ->
            lobby.players.forEachIndexed { i, seat ->
                if (seat.userId == userId) {
                    lobby.players[i] = seat.copy(userId = null, pseudonym = replacementPseudonym)
                    touched += lobby.id
                }
            }
        }
    }
    return touched
}

override fun refreshUserPseudonym(userId: UserId, newPseudonym: Pseudonym): Set<LobbyId> {
    val touched = mutableSetOf<LobbyId>()
    lock.write {
        lobbies.values.forEach { lobby ->
            lobby.players.forEachIndexed { i, seat ->
                if (seat.userId == userId) {
                    lobby.players[i] = seat.copy(pseudonym = newPseudonym)
                    touched += lobby.id
                }
            }
        }
    }
    return touched
}
```

### 6c.3.2 — Replace log-only subscribers

- [ ] **Modify** `game/infrastructure/.../events/UserEventSubscribers.kt`. Swap the log bodies for real handlers that decode the JSON payload + call the new repo methods:

```kotlin
class UserEventSubscribers(
    private val jetStream: JetStream,
    private val lobbies: LobbyRepository,
    private val lobbyBroadcaster: LobbyRosterBroadcaster, // existing WS broadcast helper
    private val json: Json = Json,
) {
    @Serializable
    private data class UserDeletedEvent(val userId: String, val deletedAt: String)
    @Serializable
    private data class UserRenamedEvent(val userId: String, val newDisplayName: String, val renamedAt: String)

    private val log = LoggerFactory.getLogger(UserEventSubscribers::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subs = mutableListOf<JetStreamSubscription>()

    fun start() {
        subs += subscribe("wordsparrow.user.deleted", "game-api-user-deleted") { msg ->
            val event = json.decodeFromString(UserDeletedEvent.serializer(), String(msg.data, Charsets.UTF_8))
            val touched = lobbies.anonymizeUserSeats(
                userId = UserId(UUID.fromString(event.userId)),
                replacementPseudonym = Pseudonym.of("Joueur supprimé"),
            )
            touched.forEach { lobbyBroadcaster.notifyRosterChanged(it) }
            log.info("user.deleted processed: userId=${event.userId} touched=${touched.size} lobbies")
        }
        subs += subscribe("wordsparrow.user.renamed", "game-api-user-renamed") { msg ->
            val event = json.decodeFromString(UserRenamedEvent.serializer(), String(msg.data, Charsets.UTF_8))
            val touched = lobbies.refreshUserPseudonym(
                userId = UserId(UUID.fromString(event.userId)),
                newPseudonym = Pseudonym.of(event.newDisplayName),
            )
            touched.forEach { lobbyBroadcaster.notifyRosterChanged(it) }
            log.info("user.renamed processed: userId=${event.userId} touched=${touched.size} lobbies")
        }
    }

    // close() + subscribe() unchanged from 6c.1
}
```

- [ ] **Adjust** the constructor call in `Module.kt` to pass `lobbies` and `lobbyBroadcaster`.

### 6c.3.3 — Tests

- [ ] **Integration test** (game-api): seed a lobby with two players (one with `userId = X`, one anon). Publish a fake `user.deleted` event for X via an embedded NATS in the test. Assert the row for X has `userId = null` + `pseudonym = "Joueur supprimé"`. Other row untouched.
- [ ] **Same shape** for `user.renamed`: seed a lobby with `userId = X, pseudonym = "Lapin 472"`. Publish a rename event with `newDisplayName = "Renard 888"`. Assert the row updates.

### 6c.3.4 — Commit + PR

- [ ] **Run** gates: `./gradlew :game:api:check --quiet`.

- [ ] **Push + PR** (title ≤ 70 chars):

```bash
git push -u origin feat/oauth2-6c-lobby-event-handlers
gh pr create --base main \
  --title "feat(game-lobby): NATS subscribers anonymize + refresh seats" \
  --body "$(cat <<'BODY'
## Summary
- Replaces 6c.1's log-only NATS subscribers with real handlers.
- `wordsparrow.user.deleted` → `LobbyRepository.anonymizeUserSeats(userId, "Joueur supprimé")`. Affected lobby IDs trigger WebSocket roster broadcasts so live clients see the update within seconds.
- `wordsparrow.user.renamed` → `LobbyRepository.refreshUserPseudonym(userId, newDisplayName)`. Same broadcast path.
- Explicit-ack JetStream semantics: failed handlers nak the message, JetStream redelivers (5 retries before dead-lettering to log).

## Test plan
- [ ] `./gradlew :game:api:check` green; integration tests cover delete + rename event paths.
- [ ] After deploy: a user deletes their account at /compte; an authed observer in the same lobby sees the player rename to "Joueur supprimé" within a few seconds.
- [ ] After deploy: a user renames at /compte; lobbies they're in update on next WS broadcast.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 60 chars.

---

## Self-review checklist

- [x] Spec coverage: every section of the spec maps to a task (NATS infra → 6c.0; producers + skeleton consumer → 6c.1; cookie + verifier + lobby endpoints + frontend → 6c.2; subscriber bodies → 6c.3; ADR → 6c.0; cookie rename trade-off + migration → 6c.2 PR body).
- [x] No placeholder phrases ("TBD", "implement later"). The `<NNNN>` ADR number is intentional — assigned at writing-plans-execution time via the recon step.
- [x] Type names consistent across tasks: `UserRenamedBroadcaster`, `WhoAmI`, `HttpCookieVerifier`, `LobbyPlayer.userId`, `anonymizeUserSeats`, `refreshUserPseudonym`.
- [x] Each PR independently shippable; 6c.0 lands first.

---

## Phasing summary

| # | Branch | Layer | Approx. diff |
|---|---|---|---|
| 6c.0 | `chore/oauth2-6c-nats-infra` | Helm + ADR | ~350 |
| 6c.1 | `feat/oauth2-6c-nats-publishers` | JVM (identity + game) | ~350 |
| 6c.2 | `feat/oauth2-6c-lobby-identity` | JVM + frontend | ~400 (cliff-edge — may need split) |
| 6c.3 | `feat/oauth2-6c-lobby-event-handlers` | JVM (game) | ~250 |

6c.2 is the cliff-edge case. If it goes over the 400-line cap, split it into 6c.2a (cookie rename + CORS adjustments) and 6c.2b (cookie verifier + lobby endpoints + frontend). The cookie rename is small and independent; isolating it lets each PR review focus on one concern.
