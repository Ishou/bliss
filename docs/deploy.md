# Deployment

How Bliss reaches production. The frontend (static bundle) ships to
Cloudflare Pages per [ADR-0004](./adr/0004-hello-world-deployment.md);
the JVM API runs on a self-managed Hetzner k3s cluster per
[ADR-0009](./adr/0009-self-managed-k8s-deployment.md). ADR-0007's
Fly.io deployment was superseded by ADR-0009 and never reached
production; the Fly section that previously lived here is retired.
This file documents the operational binding required by ADR-0004 §7
and ADR-0009.

## Pipeline

`.github/workflows/deploy-frontend.yml` builds `frontend/` on push to `main`
(production) or on any PR (preview) and uploads the static bundle to
Cloudflare Pages via Direct Upload. Cloudflare itself does not clone the
repo.

The Pages project is declared as Terraform in `terraform/`; see
`terraform/README.md` for the bootstrap procedure.

## Secrets bound by the workflow

Per ADR-0004 §7, the *names and bindings* of secrets live in repo code;
only the *values* are injected at runtime via GitHub Actions Secrets.

| Secret | Bound at | What it is | Secret? |
|---|---|---|---|
| `CLOUDFLARE_API_TOKEN` | `cloudflare/pages-action` step `apiToken` | Cloudflare API token scoped to the Pages project. | Yes |
| `CLOUDFLARE_ACCOUNT_ID` | `cloudflare/pages-action` step `accountId` | Cloudflare account UUID. Not secret in itself; stored alongside the token for convenience. | No (but treated as such for symmetry) |
| `GITHUB_TOKEN` | `cloudflare/pages-action` step `gitHubToken` | Auto-issued by GitHub Actions; lets the action comment the preview URL on the PR. | Managed |

## Required Cloudflare API token scopes

When creating the token in the Cloudflare dashboard
(My Profile -> API Tokens -> Create Token, *Custom Token*), grant the
minimum needed for Direct Upload:

- **Account -> Cloudflare Pages -> Edit** (create deployments, list
  projects).
- **User -> Memberships -> Read** (the action verifies token ownership at
  startup).

Restrict the token's *Account Resources* to the single Cloudflare account
that owns the Pages project. Set an expiry; rotate before it lapses.

Verify the live scope list against Cloudflare's docs at token creation
time — Cloudflare occasionally renames scope groups.

## Pre-deploy maintainer checklist (one-time)

Done once after this PR merges, in this order:

1. Create the Cloudflare API token with the scopes above. Copy the value;
   it is shown once.
2. Bootstrap the Pages project via Terraform:
   ```sh
   export CLOUDFLARE_API_TOKEN=<token from step 1>
   terraform -chdir=terraform/ init
   terraform -chdir=terraform/ apply -var="cloudflare_account_id=<account uuid>"
   ```
   Commit the generated `.terraform.lock.hcl`.
3. Add the same `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` as
   *GitHub Actions Secrets* (Repo Settings -> Secrets and variables ->
   Actions -> *New repository secret*).

After step 3, the next push to `main` deploys to production.

## Custom domain (`wordsparrow.io`)

Per ADR-0005 §1, the production domain is `wordsparrow.io`. The
attachment is IaC (`terraform/cloudflare-pages-domain.tf`) — the
maintainer's only manual step is DNS at the registrar (or at Cloudflare
DNS, if the zone has been transferred there).

**One-time after the next `tofu apply`:**

1. Apply the Terraform with the custom domain enabled (it is enabled
   by default; `var.custom_domain` defaults to `wordsparrow.io`):
   ```sh
   tofu -chdir=terraform/ apply -var="cloudflare_account_id=<account uuid>"
   ```
   Two new resources are created: `cloudflare_pages_domain.apex` for
   the apex and `cloudflare_pages_domain.www[0]` for the `www.` alias.
   Cloudflare returns a *Pending verification* status until DNS is set.

2. Configure DNS so Cloudflare can verify ownership and serve traffic.
   Two paths:

   **(a) Zone managed by Cloudflare DNS (recommended).** Add the zone
   in the Cloudflare dashboard, point the registrar at the issued
   nameservers, then add two records (both proxied / orange-cloud):
   - `wordsparrow.io` — `CNAME` flattening to `<project>.pages.dev`
     (Cloudflare resolves the apex CNAME automatically).
   - `www.wordsparrow.io` — `CNAME` to `<project>.pages.dev`.

   **(b) Zone managed externally.** Most registrars do not support a
   true apex CNAME. Two options:
   - If the registrar offers `ALIAS` / `ANAME`, point
     `wordsparrow.io` to `<project>.pages.dev`.
   - Otherwise, use `A` records to Cloudflare Pages's anycast IPs (see
     `https://developers.cloudflare.com/pages/configuration/custom-domains/`
     for the current list — it changes; verify before pasting).
   For `www.wordsparrow.io`, a `CNAME` to `<project>.pages.dev` works
   on every registrar.

3. Wait for verification. Cloudflare's dashboard
   (Pages -> Project -> Custom domains) flips each domain from
   *Pending* to *Active* within a few minutes once DNS resolves.
   SSL certificates are issued automatically (Cloudflare handles ACME);
   no certbot, no renewal cron.

4. (Optional) Canonicalize on the apex via a `_redirects` rule in
   `frontend/public/_redirects`:
   ```
   https://www.wordsparrow.io/* https://wordsparrow.io/:splat 301!
   ```
   Ships in a follow-up frontend workstream alongside any other
   redirect rules.

**To temporarily skip custom-domain attachment** (e.g. before the
domain is registered, or in a fork): pass
`-var="custom_domain="` to `tofu apply`. Both `cloudflare_pages_domain`
resources skip via the `count` guard and the deployment stays on the
`*.pages.dev` subdomain.

## Rollback

Per ADR-0004 §5:

- **Primary:** revert the offending commit on `main` via PR + squash-merge.
  The deploy workflow re-runs and pushes the prior bundle. GitOps-pure:
  repo state matches live state.
- **Escape hatch:** Cloudflare Pages dashboard -> *Deployments* -> select
  a prior deployment -> *Rollback*. Use only when reverting in git is
  blocked (e.g. broken build, dependency yanked). Introduces drift; open
  a follow-up PR to make the repo match.

## Deploy provenance

Every deployment is traceable from `git log`:

- Conventional-commit message identifies the workstream.
- Branch name (per `branch-name.yml`) identifies the type.
- The deploy workflow attaches the GitHub run URL to the Pages
  deployment, visible in the Cloudflare dashboard.

This satisfies ADR-0001 §9 (fleet observability) for the deploy boundary.

# Terraform k8s state backend (Hetzner Object Storage)

How the `terraform/k8s/` root reaches a working remote backend.
Authoritative spec is
[ADR-0010](./adr/0010-terraform-remote-state-hetzner.md); this section
is the operational binding required by ADR-0010 §4 (bucket bootstrap)
and §6 (credentials).

## Terraform k8s state backend — first-time bootstrap (one-time)

These are one-time, human steps run **once** before any maintainer ever
runs `tofu init` against `terraform/k8s/`. Skip if the bucket
already exists.

### 1. Create the state bucket

Pick one path:

**Path A — Hetzner Console UI (no extra tools required):**

1. Log in to the Hetzner Cloud Console.
2. Navigate to Object Storage.
3. Click **Create Bucket**:
   - Name: `bliss-tf-state`
   - Region: **FSN1** (Falkenstein)
   - Enable **Versioning**
4. Done.

**Path B — AWS CLI against the Hetzner endpoint** (if installed):

```sh
export AWS_ACCESS_KEY_ID=<s3-access-key>
export AWS_SECRET_ACCESS_KEY=<s3-secret>
aws s3api create-bucket \
  --bucket bliss-tf-state \
  --endpoint-url https://fsn1.your-objectstorage.com
aws s3api put-bucket-versioning \
  --bucket bliss-tf-state \
  --versioning-configuration Status=Enabled \
  --endpoint-url https://fsn1.your-objectstorage.com
```

### 2. Provision Hetzner Object Storage credentials

In the Hetzner Console, generate an Object Storage **access-key +
secret-key** pair scoped to the `bliss-tf-state` bucket (least
privilege; the second pair for `bliss-cnpg-backups` is provisioned by
the CNPG-wiring PR).

Store both as GitHub Actions secrets for CI:

- `S3_ACCESS_KEY`
- `S3_SECRET_KEY`

For local `tofu init`, export them under their AWS-SDK names (the
OpenTofu S3 backend reads `AWS_*` env vars even against non-AWS
endpoints):

```sh
export AWS_ACCESS_KEY_ID=<s3-access-key>
export AWS_SECRET_ACCESS_KEY=<s3-secret>
```

### 3. Initialize the backend

From `terraform/k8s/`:

```sh
tofu init
```

There is no state to migrate yet — `terraform/k8s/` declares no
resources prior to the first provider implementation PR. If you ever
run this **after** real resources have already been applied locally,
append `-migrate-state` so the existing local `terraform.tfstate` is
uploaded into the bucket:

```sh
tofu init -migrate-state
```

### 4. Verify locking

```sh
tofu plan
```

The plan should succeed (it has no resources to read; expect a "No
changes" plan), and the bucket should briefly show a
`terraform.tfstate.tflock` object during the run.

If the run 400s with `XAmzContentSHA256Mismatch`, the
`skip_s3_checksum = true` flag is missing from the backend block —
re-check `terraform/k8s/versions.tf` against ADR-0010 §2.

## Hetzner cluster bring-up (one-time)

First concrete cluster-provisioning module:
`terraform/k8s/providers/hetzner/`, wired from `terraform/k8s/main.tf`.
Spec is [ADR-0009](./adr/0009-self-managed-k8s-deployment.md). v1
footprint: 1 control plane + 1 worker, `cx22` in `fsn1`, k3s via
cloud-init.

### Prerequisites

- Hetzner Cloud project + read/write API token
  (Project → Security → API Tokens).
- The `bliss-tf-state` Object Storage bucket from the previous
  section.
- An ed25519 SSH key on the maintainer's machine — public half goes
  to `tofu apply`, private half fetches the kubeconfig.

```sh
export HCLOUD_TOKEN=<hetzner-cloud-api-token>
export AWS_ACCESS_KEY_ID=<s3-access-key>
export AWS_SECRET_ACCESS_KEY=<s3-secret>
```

### 1. Provision

From `terraform/k8s/`:

```sh
tofu init
tofu apply \
  -var "cluster_name=wordsparrow" \
  -var "region=fsn1" \
  -var "node_size=cx22" \
  -var "ssh_public_keys=[\"$(cat ~/.ssh/id_ed25519.pub)\"]"
```

Apply takes ~3–5 min. The worker's cloud-init waits on the
control-plane's `:6443/healthz` before joining, so the apply returns
only once both nodes are up.

If `kubectl get nodes` fails after apply with cloud-init reporting
`set: Illegal option -o pipefail` in
`/var/lib/cloud/instance/scripts/runcmd`, you've hit the pre-fix-PR
template (cloud-init ran the install via dash, not bash). Pull `main`,
then `tofu taint module.cluster.hcloud_server.control_plane[0]` +
`tofu taint module.cluster.hcloud_server.worker[0]` + `tofu apply` to
recreate both nodes with the fixed cloud-init. Run `ssh-keygen -R <ip>`
for each replaced node to clear the old host-key entries.

### 2. Fetch the kubeconfig (one-time human step)

ADR-0009 §10 accepts the documented-one-time-human-step pattern for
things that don't cleanly automate at v1.

```sh
CP_IP=$(tofu output -raw cluster_endpoint | sed 's|https://||;s|:6443||')
mkdir -p ~/.kube
scp -o StrictHostKeyChecking=accept-new \
  root@"$CP_IP":/etc/rancher/k3s/k3s.yaml ~/.kube/wordsparrow-prod
sed -i "s|127.0.0.1|$CP_IP|" ~/.kube/wordsparrow-prod
chmod 600 ~/.kube/wordsparrow-prod
export KUBECONFIG=~/.kube/wordsparrow-prod
kubectl get nodes  # both Ready
```

The kubeconfig is **not** committed and **not** stored in Terraform
state. CI's copy goes into a GitHub Actions secret (`KUBECONFIG`),
populated by the maintainer once after step 2.

# Platform operators bootstrap (Hetzner k8s)

Step 3 of the ADR-0009 §8 migration. Installs the four in-cluster
operators ADR-0009 §3 specifies — **cert-manager**, **ingress-nginx**,
**external-dns**, **CloudNativePG** — plus the Hetzner Cloud CSI
driver (**hcloud-csi**), the storage layer for CNPG PVCs on the
Hetzner cluster (ADR-0009 §2). Chart lives at `infra/platform/`;
subchart pins in `infra/platform/Chart.yaml`.

## One-time install

### Prereqs

- Hetzner cluster from `terraform/k8s/` already applied; the
  `~/.kube/wordsparrow-prod` kubeconfig has been retrieved per the
  previous section's step 2.
- `helm` ≥ 3.16 on PATH locally.
- `infra/platform/Chart.lock` committed. If it is absent on `main`,
  run `helm dep update infra/platform/` once and commit the lockfile
  in a sibling chore PR before continuing — `helm dep build` is the
  reproducible install step in CI.
- Local env vars exported in this shell:
  ```sh
  export CLOUDFLARE_API_TOKEN_DNS=<dns-scoped cloudflare token>
  export HCLOUD_TOKEN=<hetzner token used by terraform/k8s/ provisioning>
  export HCLOUD_TOKEN_CSI=<separate hetzner token for hcloud-csi — provision a fresh one in the Hetzner Console>
  export KUBECONFIG=~/.kube/wordsparrow-prod
  ```
  The Cloudflare token here is **distinct** from ADR-0004's
  Pages-scoped token. Required scopes: **Zone -> DNS -> Edit** +
  **Zone -> Zone -> Read** on `wordsparrow.io`.

### 1. Bootstrap secrets

Per ADR-0009 §10, two secrets are created one-time before
`helm install` (the chart never ships secret material itself):

```sh
export KUBECONFIG=~/.kube/wordsparrow-prod

kubectl create namespace platform || true

kubectl -n platform create secret generic cloudflare-api-token \
  --from-literal=cloudflare_api_token="$CLOUDFLARE_API_TOKEN_DNS"

kubectl -n platform create secret generic hcloud-csi-token \
  --from-literal=token="$HCLOUD_TOKEN_CSI"
```

Both secrets live in the `platform` namespace because `helm install
platform … -n platform` deploys all subcharts there; pods can only read
secrets from their own namespace. Stand-alone installs of these charts
typically use `kube-system` or `external-dns` — that does not apply
here because they are subcharts of the `platform` umbrella.

Each Hetzner API token is project-scoped read/write; the manifesto's
least-privilege rule applies through *blast-radius separation*, not
permission scope. A leak from a CSI driver pod must not also
compromise the credential that provisions cluster nodes. Generate the
CSI token in the Hetzner Console as a sibling of the Terraform one —
independent rotation, independent revocation.

The CNPG backups bucket (`bliss-cnpg-backups`, ADR-0010 §5) and its
S3 credential pair are wired by the WordSparrow chart in step 4, not
here.

### 2. Install the platform chart

```sh
helm dep update infra/platform/   # or `helm dep build` once Chart.lock is on main

helm install platform infra/platform/ \
  -n platform --create-namespace \
  -f infra/platform/values-prod.yaml \
  --set clusterIssuer.letsencrypt.email="<your-email>"
```

The `--set` is required — the Let's Encrypt ClusterIssuer template
fails-fast (`required`) if the ACME contact email is missing.

### 3. Verify

```sh
kubectl get pods -A                        # cert-manager, ingress-nginx, external-dns, cnpg controller, hcloud-csi nodes all Running
kubectl get clusterissuer letsencrypt-prod # Ready=True after a few seconds (ACME registration)
kubectl get crd | grep cnpg.io
kubectl get sc hcloud-volumes              # default StorageClass for CNPG PVCs
```

### 4. Next

Install the WordSparrow API Helm chart from `grid/api/deploy/chart/`
per its README — step 4 of the ADR-0009 §8 migration. Before that
install, create the app-level secrets per
`# Application secrets bootstrap (one-time)` below; the API pod's
`envFrom` resolves at pod-create and will fail otherwise. Once the
chart lands, `https://api.wordsparrow.io/v1/health` returns 200 once
external-dns has written the A + TXT records and cert-manager has
issued the production cert against `letsencrypt-prod`.

DNS cutover (ADR-0009 §8 step 6) has landed: `api.wordsparrow.io` is
owned by in-cluster `external-dns`, not Terraform. The historical
TF-managed CNAME pointing at Fly is gone.

### Troubleshooting

- **`letsencrypt-prod` stuck `Ready=False`:** ACME order may be
  rate-limited if you've installed the chart multiple times within
  the hour. Wait ~1h or temporarily switch to the staging issuer
  (out of scope here).
- **CNPG cluster won't bind its PVC:** confirm the
  `hcloud-csi-token` secret exists in `platform` and that the
  Hetzner project quota allows new volume creation in `fsn1`.
- **external-dns not writing records:** check
  `kubectl -n platform logs deploy/external-dns` for Cloudflare
  auth errors. Most common cause: the token is missing the
  **Zone:DNS:Edit** scope, or `wordsparrow.io` is not in the token's
  zone-resources allow-list.

# Application secrets bootstrap (one-time)

> **Run this section BEFORE the step-4 Helm chart install.** Both secrets
> must exist in the `wordsparrow` namespace before `helm install` (or
> the CD workflow's first deploy), or the API pod will fail
> `CreateContainerConfigError` at pod-create time. The chart's
> `envFrom: secretRef` resolves at pod-create, not at runtime.

The WordSparrow API chart (`grid/api/deploy/chart/`) consumes two
app-level secrets that the chart never ships itself, per the
ADR-0009 §10 interim secrets-bootstrap pattern (`kubectl create
secret`, values never committed):

- `wordsparrow-api-env` — `envFrom` source for the API pod
  (`values.yaml` `envFromSecret: "wordsparrow-api-env"`); must contain
  at minimum `DATABASE_URL`.
- `cnpg-backup-creds` — S3 credentials referenced by the CNPG
  `Cluster` CR's `barmanObjectStore` block
  (`templates/postgres-cluster.yaml`), pointing at the
  `bliss-cnpg-backups` Hetzner Object Storage bucket.

These were missed during the 2026-04-26 prod deploy and discovered
the hard way; this section is the binding so it does not happen again.

## Prereqs

- Platform operators installed per the previous section. The CNPG
  controller is Running; the `<cluster>-app` secret will be
  auto-generated by CNPG once the WordSparrow chart's `Cluster` CR
  lands (it does not exist yet at this point).
- `KUBECONFIG=~/.kube/wordsparrow-prod` exported.
- `S3_ACCESS_KEY` + `S3_SECRET_KEY` exported. These are the same
  Hetzner Object Storage credentials used during the platform
  bootstrap; for the CNPG backups bucket, provision a dedicated key
  pair scoped to `bliss-cnpg-backups` (least privilege; blast-radius
  separation from the `bliss-tf-state` pair).

## 1. Bootstrap `wordsparrow-api-env`

This secret is consumed by the WordSparrow API pod via `envFrom`
(chart `values.yaml`: `envFromSecret: "wordsparrow-api-env"`). It
must contain at minimum `DATABASE_URL` pointing at the CNPG
cluster's read-write service.

**Two-pass install required.** The secret must exist *before* the
chart installs because `envFrom` is mandatory at pod-create time —
the API Deployment won't schedule without it. CNPG's
`<cluster>-app` secret (the real `DATABASE_URL` source) only exists
*after* the cluster comes up, which only happens *after* the chart
installs. Hence: install with a placeholder, then swap.

```sh
# Pass 1: create a placeholder so the chart can install
kubectl create namespace wordsparrow || true
kubectl -n wordsparrow create secret generic wordsparrow-api-env \
  --from-literal=DATABASE_URL="postgres://placeholder@wordsparrow-api-pg-rw:5432/wordsparrow"

# (Run helm install per CD or manual)

# Pass 2: after CNPG cluster is Ready, swap in the real URI
REAL_URI=$(kubectl -n wordsparrow get secret wordsparrow-api-pg-app \
  -o jsonpath='{.data.uri}' | base64 -d)

kubectl -n wordsparrow create secret generic wordsparrow-api-env \
  --from-literal=DATABASE_URL="$REAL_URI" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n wordsparrow rollout restart deploy/wordsparrow-api
```

## 2. Bootstrap `cnpg-backup-creds`

This secret feeds the CNPG `Cluster` CR's `barmanObjectStore` block
(see `grid/api/deploy/chart/templates/postgres-cluster.yaml` —
`accessKeyId` and `secretAccessKey` reference keys `ACCESS_KEY_ID`
and `ACCESS_SECRET_KEY` on this secret). Without it, CNPG cannot
configure backups and the `Cluster` will not reach `Ready=True`.

```sh
kubectl -n wordsparrow create secret generic cnpg-backup-creds \
  --from-literal=ACCESS_KEY_ID="$S3_ACCESS_KEY" \
  --from-literal=ACCESS_SECRET_KEY="$S3_SECRET_KEY"
```

## 3. Verify

```sh
kubectl -n wordsparrow get secret wordsparrow-api-env cnpg-backup-creds
kubectl -n wordsparrow get cluster wordsparrow-api-pg     # CNPG cluster Ready=True
kubectl -n wordsparrow get pods                            # API + 3 PG instances Running
```

## Troubleshooting

- **API pod stuck `CreateContainerConfigError`**: check
  `kubectl describe pod` for "secret not found" — one of the two
  bootstrap steps was skipped. Re-run the missing step and the pod
  will recover on its next sync.
- **API pod `CrashLoopBackOff` with DB connection error**: the
  placeholder `DATABASE_URL` is still in the secret. Re-run pass 2
  of step 1 to swap in the real URI from
  `wordsparrow-api-pg-app`, then `kubectl rollout restart`.
- **CNPG `Cluster` not `Ready`**: confirm `cnpg-backup-creds` exists
  in the `wordsparrow` namespace. Missing creds block the
  `barmanObjectStore` reconcile, which blocks the cluster from
  reaching `Ready=True`.

## Forward-pointer

The durable fix is to wire the chart's `envFrom` directly to CNPG's
`<cluster>-app` secret, eliminating both the placeholder and the
manual pass-2 swap. Tracked as a follow-up PR against
`grid/api/deploy/chart/`.

