# wordsparrow-api Helm chart

Step 4 of the ADR-0009 §8 migration. Skeleton chart for the WordSparrow
JVM API, the CNPG `Cluster` that backs it, and the `Ingress` that
fronts it.

## What this chart deploys

- `Deployment` + `Service` for the Ktor API (`grid/api/`), port 8080,
  `/v1/health` for liveness/readiness.
- `Ingress` (ingress-nginx) with cert-manager + external-dns
  annotations from values.
- CloudNativePG `Cluster` CR for the Postgres backing store.
- CloudNativePG `ScheduledBackup` (prod only — `postgres.backups.enabled=true`)
  pointing at `bliss-cnpg-backups` on Hetzner Object Storage (ADR-0010 §5).

Out of scope: the cluster (step 3 — `terraform/k8s/` + operators), the
image build (sibling PR), and the CD workflow (step 5).

## Render and lint

```sh
cd grid/api/deploy/chart
helm lint . -f values-local.yaml && helm template wordsparrow-api . -f values-local.yaml
helm lint . -f values-prod.yaml  && helm template wordsparrow-api . -f values-prod.yaml
```

## One-time bootstrap before `helm install`

Two Kubernetes Secrets must exist before this chart installs cleanly,
created with `kubectl create secret` per ADR-0009 §10. The recipe lands
in `docs/deploy.md` alongside the cluster bring-up PR.

- `wordsparrow-api-env` — env vars the API reads at startup. Referenced
  by `values.envFromSecret`.
- `cnpg-backup-creds` — Hetzner Object Storage credentials for CNPG.
  Referenced by the backup block when `postgres.backups.enabled=true`.

Embedding plaintext would violate "secrets never in code", so neither
secret is templated by this chart.

## `image.requireDigest`

Defaults to `false`; set to `true` in `values-prod.yaml` so `helm` aborts
if the CD workflow has not injected `image.digest` (manifesto: container
images pinned to digest, not tag).
