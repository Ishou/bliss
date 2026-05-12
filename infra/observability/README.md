# Observability — SigNoz backend

WordSparrow observability backend per [ADR-0027](../../docs/adr/0027-observability-backend-signoz.md). Wraps the upstream SigNoz Helm chart with WordSparrow-specific overrides:

- Right-sized resource requests for the cx33 worker.
- ClickHouse + ZooKeeper PVCs on Hetzner Cloud Volumes (`hcloud-volumes`).
- Two ingresses (`errors.wordsparrow.io` + `dashboard.wordsparrow.io`) targeting the SigNoz frontend Service, both gated by ingress-nginx basic auth (htpasswd) per [ADR-0028](../../docs/adr/0028-admin-url-auth-htpasswd.md).
- The chart's own ingress is **disabled** — we manage two parallel ingresses here so the auth + DNS-target annotations stay aligned with the rest of the WordSparrow charts (matomo, grid/api, game/api).

Out of scope for this PR (queued for follow-ups):

- Backend OTel Java agent on grid/api + game/api (PR-E)
- Frontend browser-side OTel SDK wiring (PR-F.2). The public ingest endpoint at `otlp.wordsparrow.io` ships in PR-F.1 (this chart, ADR-0033); the actual SDK + sampler land in the frontend bundle in F.2.
- htpasswd-gating Matomo (`analytics.wordsparrow.io`) — same Secret pattern, separate chart (PR-G)
- Cut SigNoz retention via UI (Settings → General → Data retention): 3d traces / 14d metrics / 3d logs (was 7d/30d/3d defaults).

## One-time install

### Prereqs

- Hetzner cluster up; platform chart (cert-manager, ingress-nginx, external-dns, hcloud-csi) installed per `docs/deploy.md`.
- `helm` ≥ 3.16 on PATH locally.
- `KUBECONFIG=~/.kube/wordsparrow-prod` exported.
- `htpasswd` available locally (`apt install apache2-utils` on Linux, `brew install httpd` on macOS).

### 1. Bootstrap the admin-htpasswd Secret

```sh
./infra/observability/scripts/bootstrap-admin-htpasswd.sh
```

The script:

1. Generates a 32-character random password.
2. Computes its bcrypt htpasswd hash.
3. Creates (or rotates, with `--rotate`) the `admin-htpasswd` Secret in the `observability` namespace.
4. Prints the **plaintext password to stdout exactly once** for the operator to save in their password manager.

The plaintext is never written to disk and never recoverable from the cluster (the Secret stores only the bcrypt hash).

### 2. Pull subchart tarballs

```sh
helm dep update infra/observability/
```

### 3. Install the chart

```sh
helm install observability infra/observability/ \
  -n observability \
  -f infra/observability/values-prod.yaml \
  --wait --timeout 15m
```

The `--timeout 15m` is generous on purpose — ClickHouse + ZooKeeper provisioning + first-run schema bootstrap can flirt with the 10-min mark on a cold cluster.

### 4. Verify

```sh
kubectl -n observability get pods                  # all Running, signoz-frontend Ready=True
kubectl -n observability get ingress               # both hosts have the floating IP in ADDRESS
dig +short errors.wordsparrow.io @1.1.1.1          # 116.202.180.82
dig +short dashboard.wordsparrow.io @1.1.1.1       # 116.202.180.82
```

Then visit either URL in a browser — the basic-auth prompt appears (use `admin` + the password from step 1), and SigNoz's UI loads.

## Day-2

### Rotate the admin password

```sh
./infra/observability/scripts/bootstrap-admin-htpasswd.sh --rotate
kubectl -n platform rollout restart deploy/platform-ingress-nginx-controller
```

The ingress-nginx restart forces an immediate reload of the auth file (otherwise it'd take ~5 min for the controller to pick up the Secret update on its own).

### Configure the launch symptom alert

ADR-0032 defines a single launch alert: API 5xx error rate > 1% over
5 minutes, delivered via Gmail SMTP relay. The rule itself lives in
SigNoz's DB once you create it via the UI; the version-controlled
spec is at [`alerts/api-5xx-error-rate.md`](./alerts/api-5xx-error-rate.md).

One-time setup:

1. **Generate a Google App Password** at https://myaccount.google.com/apppasswords.
   Save the 16-character output to your password manager.
2. **In SigNoz UI** → Settings → Channels → New Channel → Email,
   create a channel named `gmail-relay` using the SMTP table in
   `alerts/api-5xx-error-rate.md`. Hit "Test" before saving — a test
   email should land in your inbox within ~10s.
3. **Create the alert rule** following the Query section of the spec.
   Either Builder mode (filter `service.name in [grid-api, game-api]`,
   aggregate count, formula `errors / total > 0.01`) or paste the SQL.
   Bind the channel to the rule.
4. **Smoke-test** with the curl one-liner at the bottom of the spec.
   Wait 5–7 minutes for the email; if it doesn't arrive, see the
   troubleshooting bullets in the spec.

After a SigNoz reinstall, repeat steps 2–3 from the same spec.

### Smoke-test the public OTLP endpoint

Once `helm upgrade` lands PR-F.1 in prod (and the cert-manager + external-dns reconcile, ~5 min), the browser ingest endpoint should accept POSTs from a `wordsparrow.io` Origin and reject everything else:

```sh
# 200 expected (allowed Origin)
curl -i -X POST https://otlp.wordsparrow.io/v1/traces \
  -H 'Origin: https://wordsparrow.io' \
  -H 'Content-Type: application/x-protobuf' \
  --data-binary @/dev/null

# 403 expected (Origin not in allow-list)
curl -i -X POST https://otlp.wordsparrow.io/v1/traces \
  -H 'Origin: https://example.com' \
  -H 'Content-Type: application/x-protobuf' \
  --data-binary @/dev/null

# 200 expected on OPTIONS preflight from a wordsparrow.io Origin
curl -i -X OPTIONS https://otlp.wordsparrow.io/v1/traces \
  -H 'Origin: https://wordsparrow.io' \
  -H 'Access-Control-Request-Method: POST'
```

If abuse appears, disable the Ingress immediately:

```sh
helm upgrade observability infra/observability/ \
  -n observability \
  -f infra/observability/values-prod.yaml \
  --set ingress.otlpPublic.enabled=false
```

ADR-0033 §5 enumerates the longer-term mitigations (tighter rate limit, same-origin proxy, auth proxy).

### Topology follow-ups

- 2026-05-12: SigNoz now runs on a dedicated `bliss.io/role=observability`-tainted
  worker (Terraform: `observability_worker_count = 1`, `observability_worker_node_size
  = "cx32"`). Apply terraform first; pods will pend on the taint until the worker
  joins. ~10 min provision time.
- 2026-05-12: Applied docs-recommended tuning: ZK heap 1024→512 MB (Apache guide,
  ≤75% of 768Mi container memory), ClickHouse mark_cache 500 MB /
  max_server_memory_usage_to_ram_ratio 0.7 / verbose log tables disabled
  (ClickHouse low-RAM tuning, [operations/tips](https://clickhouse.com/docs/en/operations/tips)).
- 2026-05-12: Restart-speed PR: pre-pull DaemonSet on observability worker
  keeps SigNoz/ClickHouse/ZK images warm. ClickHouse
  max_part_loading_threads bumped 8 -> 16. Expected ~30-60s savings on
  cross-node restarts plus faster CH cold start on every restart.

### Bump the SigNoz subchart

Edit `Chart.yaml`'s `dependencies[].version`, then:

```sh
helm dep update infra/observability/
helm upgrade observability infra/observability/ \
  -n observability \
  -f infra/observability/values-prod.yaml
```

Pin deliberately; no floating versions (per MANIFESTO Reproducible Builds).

## Architecture

```
                ┌─────────────────────────────┐
  Browser ─────▶│  ingress-nginx (FIP)        │
                │  htpasswd auth (admin-htp.) │
                └────────────┬────────────────┘
                             │
                             ▼
                ┌─────────────────────────────┐
                │  signoz-frontend Service    │   ← chart-managed
                │  (ClusterIP, port 3301)     │
                └────────────┬────────────────┘
                             │
                             ▼
                ┌─────────────────────────────┐
                │  query-service ─▶ ClickHouse │
                │                  ZooKeeper  │
                └─────────────────────────────┘

  apps (PR-E, PR-F)
  ─OTLP─▶  signoz-otel-collector Service (in-cluster)
                             │
                             ▼
                       (same backend)
```
