# OAuth2 Player Sign-In — Phase 4.5a: Dockerfile + Helm chart

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship the deployable artifacts for `identity-api` — multi-stage Dockerfile producing `bliss-identity-api:0.1.0`, and a Helm chart at `identity/api/deploy/chart/` covering Deployment + Service + Ingress + ServiceAccount + `values.yaml` + `values-local.yaml`. Mirrors the `game/api/deploy/chart/` shape with three deltas: (1) the service runs on port 8082, (2) replicas can scale (sessions live in Postgres, not memory) — set `replicas: 2` with `RollingUpdate`, (3) the envFromSecret carries OIDC client credentials + cookie-domain + return-origin config.

**Architecture:**
- `identity/api/Dockerfile` — multi-stage (JDK builder → JRE runtime), pins same base-image digests as game/api, copies the existing build scripts to keep dep-resolve cached. Uses `:identity:api:shadowJar`.
- Helm chart: hand-rolled, mirrors `bliss-game-api` chart with the deltas above and identity-flavored env vars.
- `values-prod.yaml`, db-chart (CNPG), workflow extensions, terraform DNS, docs/deploy.md, and OAuth client registration runbook are deferred to PRs 4.5b–4.5e.

**Tech stack:** Helm 3, Kubernetes 1.30, JDK 21, Ktor 3.4.3.

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b chore/oauth2-identity-helm-chart
```

---

## File Structure

**Create:**
- `identity/api/Dockerfile` — multi-stage; produces `bliss-identity-api`.
- `identity/api/deploy/chart/Chart.yaml`
- `identity/api/deploy/chart/.helmignore`
- `identity/api/deploy/chart/values.yaml`
- `identity/api/deploy/chart/values-local.yaml`
- `identity/api/deploy/chart/templates/_helpers.tpl`
- `identity/api/deploy/chart/templates/deployment.yaml`
- `identity/api/deploy/chart/templates/service.yaml`
- `identity/api/deploy/chart/templates/ingress.yaml`
- `identity/api/deploy/chart/templates/serviceaccount.yaml`

(No `values-prod.yaml` in this PR — that lands in 4.5b alongside the DNS+TLS work so prod ingress is reviewed in one shot.)

---

## Task: PR 4.5a — Dockerfile + chart skeleton

**Branch:** `chore/oauth2-identity-helm-chart`.

### 0 — Recon

- [ ] **Step 0a:** Read `game/api/Dockerfile`. Note the exact pinning (`eclipse-temurin:21.0.10_7-jdk-alpine-3.22` + digest, JRE runtime digest, OTel javaagent v2.27.0 + checksum, USER 1000:1000, EXPOSE 8081). Mirror exactly, swapping the module to `:identity:api:shadowJar`, port to **8082**, output jar to `identity-api.jar`, OCI description to `WordSparrow Identity JVM API (Ktor REST)`. The COPY block already references `identity/{domain,application,infrastructure,api}/build.gradle.kts` — confirm it does (it does per the comment "Same gotcha for :identity:* modules introduced in ADR-0044") so no changes are needed there beyond the new Dockerfile copying its OWN src/ tree.

- [ ] **Step 0b:** Read `game/api/deploy/chart/` files in full: `Chart.yaml`, `values.yaml`, `values-local.yaml`, `templates/*`. These are your templates.

- [ ] **Step 0c:** Read `grid/api/deploy/chart/values.yaml` to spot patterns specific to a single-host REST service (vs game's WebSocket-flavored config). Identity should follow grid's WebSocket-less pattern (no `proxy-read-timeout=3600`, no `proxy-http-version=1.1`).

- [ ] **Step 0d:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt` to confirm the default port and the `IDENTITY_DATABASE_URL` env var name; verify what `IdentityApiConfig.fromEnv()` requires. The chart's env block must populate every `requireEnv(...)` value.

### 1 — Dockerfile

- [ ] **Step 1:** Create `identity/api/Dockerfile`. Start from a copy of `game/api/Dockerfile`, then change:
  - Comments referencing `game/api` → `identity/api`.
  - Stage 1: `COPY identity/{domain,application,infrastructure,api}/src` instead of game's. The existing top-half (`COPY grid/.../build.gradle.kts` etc.) stays — every module's build script is needed for Gradle configuration phase.
  - Stage 1: `./gradlew :identity:api:shadowJar`.
  - Stage 2: `COPY --from=build /workspace/identity/api/build/libs/identity-api-*-all.jar /app/identity-api.jar`.
  - `EXPOSE 8082`. `HEALTHCHECK ... http://localhost:8082/v1/health`.
  - `CMD ["java", "-jar", "/app/identity-api.jar"]`.
  - `LABEL org.opencontainers.image.description="WordSparrow Identity JVM API (Ktor REST)"`.

- [ ] **Step 2:** Verify the build works locally:

```bash
docker build -f identity/api/Dockerfile -t bliss-identity-api:0.1.0 .
```

Expected: succeeds, image tagged. If the user doesn't have Docker running, skip — `helm-lint` + `api-chart-lint` workflows in CI will catch chart issues; the Docker build is exercised by the build-and-push-image workflow in PR 4.5d.

- [ ] **Step 3:** Commit:

```bash
git add identity/api/Dockerfile
git commit -s -m "chore(identity-api): add multi-stage Dockerfile"
```

### 2 — Chart skeleton

- [ ] **Step 4:** Create `identity/api/deploy/chart/Chart.yaml`:

```yaml
apiVersion: v2
name: bliss-identity-api
description: "Bliss player identity backend (Ktor REST + OIDC)"
type: application
version: 0.1.0
appVersion: "0.1.0"
# Cluster operators (cert-manager, ingress-nginx, external-dns) are
# cluster-installed via cluster bring-up (ADR-0009 §3), not as subcharts.
# The CNPG Cluster backing this api lives in a sibling chart at
# `identity/api/deploy/db-chart/` (chart `bliss-identity-api-pg`,
# release `wordsparrow-identity-api-pg`) — different lifecycle from the
# api Deployment. See ADR-0039 amendment 2026-05-13 for the rationale;
# the db-chart itself lands in PR 4.5c.
dependencies: []
```

- [ ] **Step 5:** Create `identity/api/deploy/chart/.helmignore` (10 lines, copy verbatim from `game/api/deploy/chart/.helmignore`):

```
# Helm packaging excludes — same as game/api chart.
.DS_Store
.git/
.gitignore
.bzr/
.hg/
.svn/
*.tmproj
.idea/
.vscode/
```

- [ ] **Step 6:** Create `templates/_helpers.tpl`. Copy from `game/api`'s helpers, find-and-replace `bliss-game-api` → `bliss-identity-api`. The shape is identical.

### 3 — Templates

- [ ] **Step 7:** Create `templates/deployment.yaml`. Mirror `game/api`'s with these deltas:
  - Header comment block explains the chart name + ADRs.
  - `replicas: {{ .Values.replicaCount | default 2 }}` (NOT hard-coded 1 — identity has no in-memory state; sessions are in Postgres).
  - `strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 1, maxUnavailable: 0 } }`.
  - Keep the `wait-for-postgres` init-container block (identity uses Flyway too) — `pg_isready -U identity -d identity`.
  - The `env:` block reads `DATABASE_URL` from `<clusterName>-app` Secret's `uri` key — same pattern. For identity, **also expose `IDENTITY_DATABASE_URL` from the same Secret** (the app reads `IDENTITY_DATABASE_URL`, not `DATABASE_URL`):

    ```yaml
    - name: IDENTITY_DATABASE_URL
      valueFrom:
        secretKeyRef:
          name: {{ .Values.database.clusterName }}-app
          key: uri
    ```

  - Keep the `volumeMounts: [{name: tmp, mountPath: /tmp}]` block.
  - Probe paths: `/v1/health` (same as identity's HealthRoute).

- [ ] **Step 8:** Create `templates/service.yaml`. Copy game's verbatim, just swap helper names via the chart find-and-replace.

- [ ] **Step 9:** Create `templates/serviceaccount.yaml`. Copy game's verbatim. Keep the comment about secrets being bootstrapped via `kubectl create secret`.

- [ ] **Step 10:** Create `templates/ingress.yaml`. Copy from `grid/api/deploy/chart/templates/ingress.yaml` (NOT game's — identity has no WebSocket so doesn't need the `proxy-read-timeout=3600` annotations). Standard rules-based ingress with TLS conditional on `tlsSecretName`.

### 4 — Values

- [ ] **Step 11:** Create `values.yaml`. Mirror `game/api/deploy/chart/values.yaml` with these changes:
  - `image.repository: ghcr.io/ishou/bliss/wordsparrow-identity-api`
  - `service.port: 8082`
  - `database.clusterName: "wordsparrow-identity-api-pg"`
  - `database.enabled: false` (default off; prod overrides to true).
  - Remove all `GRID_BASE_URL` env (identity doesn't call grid).
  - Replace `OTEL_SERVICE_NAME` value with `"identity-api"`.
  - Replace `OTEL_RESOURCE_ATTRIBUTES` value with `"bounded_context=identity"`.
  - Keep `JAVA_TOOL_OPTIONS`, `PORT=8082`, `OTEL_EXPORTER_OTLP_*`, `OTEL_TRACES_SAMPLER*` exactly as game's.
  - `ingress.host: ""` (filled per-env).
  - `ingress.path: "/"`.
  - `ingress.annotations: {}`.
  - `envFromSecret: ""` (filled per-env, default empty for local-without-OIDC tests).
  - Drop the long ADR-0018-flavored comments specific to game's in-memory state.

- [ ] **Step 12:** Create `values-local.yaml`. Mirror `game/api`'s with:
  - `image.repository` unchanged (uses the default from values.yaml).
  - `image.tag: "0.1.0"`, `image.pullPolicy: Never`.
  - `ingress.host: "localhost"`.
  - `ingress.tlsSecretName: "bliss-identity-api-tls"`.
  - `ingress.annotations: { cert-manager.io/cluster-issuer: "selfsigned" }`.
  - `envFromSecret: "bliss-identity-api-env"`.

### 5 — Lint

- [ ] **Step 13:** Run helm lint locally:

```bash
helm lint identity/api/deploy/chart/ --strict
helm lint identity/api/deploy/chart/ -f identity/api/deploy/chart/values-local.yaml --strict
```

Both must pass. If the CLI isn't installed locally, rely on the `helm-lint.yml` CI workflow once the PR is open.

- [ ] **Step 14:** Render the chart against local values to verify the deployment renders:

```bash
helm template wordsparrow-identity-api identity/api/deploy/chart/ \
  -f identity/api/deploy/chart/values-local.yaml \
  --set ingress.host=localhost \
  > /tmp/identity-rendered.yaml
```

Inspect `/tmp/identity-rendered.yaml` — should produce 4 resources: ServiceAccount, Service, Deployment, Ingress. No `IDENTITY_DATABASE_URL` env var because `database.enabled=false` by default in values.yaml. (4.5b's values-prod sets enabled=true.)

- [ ] **Step 15:** Build + commit:

```bash
./gradlew :identity:api:build --quiet   # confirm shadowJar still builds
git add identity/api/deploy/chart/
git commit -s -m "chore(identity-api): scaffold Helm chart (no values-prod yet)"
```

### 6 — Helm-lint workflow registration

- [ ] **Step 16:** Read `.github/workflows/helm-lint.yml`. Add `identity/api/deploy/chart/` to whatever chart-path matrix or list it uses, mirroring how game's chart is registered. If the workflow uses a glob (`game/api/deploy/chart/`), check whether the glob already catches identity (e.g. `*/api/deploy/chart/`) — if so, no change needed.

- [ ] **Step 17:** Read `.github/workflows/api-chart-lint.yml` similarly. Add identity if needed.

- [ ] **Step 18:** Commit if either workflow changed:

```bash
git add .github/workflows/
git commit -s -m "ci(identity-api): wire chart into helm-lint + api-chart-lint workflows"
```

### 7 — PR

- [ ] **Step 19:** Size check:

```bash
git diff origin/main --shortstat
```

If over 400 added lines excluding blanks, the obvious cut is to drop `templates/ingress.yaml` + `values-local.yaml`'s ingress block (move them to 4.5b). Without ingress the service still deploys; routing lands later.

- [ ] **Step 20:** Push + open PR:

```bash
git push -u origin chore/oauth2-identity-helm-chart
gh pr create --base main \
  --title "chore(identity-api): Dockerfile + base Helm chart" \
  --body "$(cat <<'BODY'
## Summary
- Adds `identity/api/Dockerfile` — multi-stage build (JDK 21 → JRE 21 Alpine, both pinned to digest); runs `:identity:api:shadowJar` on port 8082; bundles the OpenTelemetry javaagent (v2.27.0, same checksum as grid/game) per ADR-0027.
- Adds `identity/api/deploy/chart/` — Deployment (replicas: 2, RollingUpdate; identity has no in-memory state, sessions are in Postgres), Service (ClusterIP, port 8082), Ingress (no WebSocket annotations — identity is REST-only), ServiceAccount, helpers; base `values.yaml` + `values-local.yaml`. `IDENTITY_DATABASE_URL` is sourced from the CNPG `<clusterName>-app` Secret when `database.enabled=true`.
- Wires the chart into `helm-lint.yml` + `api-chart-lint.yml`.
- `values-prod.yaml`, the CNPG db-chart, terraform DNS, and CI build/deploy workflows ship in PRs 4.5b–4.5e.

## Test plan
- [ ] `helm lint identity/api/deploy/chart/ --strict` — green.
- [ ] `helm lint identity/api/deploy/chart/ -f .../values-local.yaml --strict` — green.
- [ ] `helm template ... -f values-local.yaml` renders 4 resources (ServiceAccount, Service, Deployment, Ingress) without errors.
- [ ] `docker build -f identity/api/Dockerfile -t bliss-identity-api:0.1.0 .` — succeeds (run locally if Docker is available; otherwise CI's build-and-push workflow catches it).
- [ ] `./gradlew :identity:api:build` — still green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 50 chars.

---

## Future plans

- **4.5b** — `values-prod.yaml` (host `auth.wordsparrow.io`, cert-manager letsencrypt-prod issuer, external-dns annotations, the `bliss-identity-api-env` secret reference, rate limits).
- **4.5c** — CNPG db-chart at `identity/api/deploy/db-chart/` mirroring `game/api/deploy/db-chart/`.
- **4.5d** — CI: `build-and-push-image.yml` matrix entry + `deploy-api-k8s.yml` matrix entry for identity-api (and identity-db).
- **4.5e** — Terraform A record for `auth.wordsparrow.io` (in `terraform/cloudflare-dns-records.tf`) + `docs/deploy.md` update + Google/Apple OAuth client registration runbook + `bliss-identity-api-env` secret bootstrap script.
