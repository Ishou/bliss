# Local Development — Kubernetes parity with k3d

This guide gets a developer from a fresh clone to a running local
Kubernetes cluster that mirrors what we run in production. We use
[k3d](https://k3d.io) (k3s-in-Docker) locally and self-managed
[k3s](https://k3s.io) on Hetzner in production — same distribution,
same kubectl, same manifests, same operator set.

> The production counterpart is described in **ADR-0009: self-managed
> k3s on Hetzner** (companion PR; link will resolve once merged) and in
> `terraform/k8s/README.md` (added in a later PR).

## 1. Prerequisites

You need four CLIs on your `PATH`. Versions below are the floors we
test against.

| Tool      | Floor      | Install hint                                                                          |
| --------- | ---------- | ------------------------------------------------------------------------------------- |
| Docker    | engine 24+ | macOS: Docker Desktop. Linux: distro package or <https://docs.docker.com/engine/install/> |
| `k3d`     | v5.6+      | `brew install k3d` or `curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh \| bash` |
| `kubectl` | v1.30+     | `brew install kubectl` or <https://kubernetes.io/docs/tasks/tools/>                   |
| `helm`    | v3.14+     | `brew install helm` or `curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 \| bash` |

`scripts/local-cluster.sh` will tell you (with the install URL) which
one is missing if you skip a step. It will not install anything for
you — installation is your platform's job.

## 2. Cluster lifecycle

All commands are wrappers around `scripts/local-cluster.sh`:

```sh
make cluster-up         # create the cluster (idempotent)
make cluster-bootstrap  # install operators (ingress-nginx, cert-manager, CNPG)
make cluster-status     # kubectl get nodes,pods -A
make cluster-reset      # nuke and recreate
make cluster-down       # delete the cluster
```

A typical first-time bring-up looks like:

```text
$ make cluster-up
[local-cluster] creating cluster 'wordsparrow-local' (image rancher/k3s:v1.30.6-k3s1)
INFO[0000] Prep: Network
INFO[0000] Created network 'k3d-wordsparrow-local'
INFO[0012] Cluster 'wordsparrow-local' created successfully!
[local-cluster] kubectl context set to k3d-wordsparrow-local
[local-cluster] next: make cluster-bootstrap

$ make cluster-bootstrap
[local-cluster] installing ingress-nginx 4.11.3
[local-cluster] installing cert-manager v1.16.1 (self-signed locally)
[local-cluster] installing cloudnative-pg operator 0.22.1
[local-cluster] bootstrap complete. external-dns is intentionally NOT installed locally;

$ make cluster-status
NAME                              STATUS   ROLES                  AGE   VERSION
node/k3d-wordsparrow-local-server-0   Ready    control-plane,master   1m    v1.30.6+k3s1
node/k3d-wordsparrow-local-agent-0    Ready    <none>                 1m    v1.30.6+k3s1
...
```

The kubeconfig is written to `~/.kube/config` by k3d under the context
name `k3d-wordsparrow-local`. **Do not commit it.**

## 3. What runs locally

After `make cluster-bootstrap` you have:

- **ingress-nginx** — same controller as prod, same chart.
- **cert-manager** — installed with CRDs; locally configured to use a
  self-signed `ClusterIssuer` (see divergence below).
- **CloudNative-PG (CNPG)** — the operator only. Postgres clusters are
  created by per-app Helm releases (later PR).

### Divergences from prod (deliberate, documented)

| Concern         | Prod (Hetzner k3s)                    | Local (k3d)                        | Why diverge                                         |
| --------------- | ------------------------------------- | ---------------------------------- | --------------------------------------------------- |
| DNS             | `external-dns` writes Cloudflare records | none — use `/etc/hosts`         | no DNS provider locally; not worth a token round-trip |
| TLS issuance    | cert-manager + LetsEncrypt (DNS-01)   | cert-manager + self-signed issuer  | LE rate-limits + needs public DNS                   |
| Postgres storage| CNPG cluster on local-path PVCs       | CNPG cluster on `emptyDir`         | container teardown shouldn't leak host volumes      |
| Replicas        | 3 (Postgres), 2 (ingress)             | 1 each                             | laptop budget                                       |

To reach an Ingress locally, add a hosts entry once:

```sh
echo "127.0.0.1 wordsparrow.local" | sudo tee -a /etc/hosts
```

then `https://wordsparrow.local/` resolves through k3d's load balancer
to ingress-nginx. The cert will be self-signed; your browser will warn.

## 4. Parity guarantee

The principle (per `MANIFESTO.md` "Dev/prod parity"): the **same
manifests and the same Helm charts** ship in both environments. Only
the values file changes.

Planned layout (lands with the WordSparrow chart in a later PR):

```
deploy/
├── wordsparrow/                 # the app's Helm chart
│   ├── Chart.yaml
│   └── templates/
├── values-local.yaml            # self-signed issuer, emptyDir PG, 1 replica
└── values-prod.yaml             # LE issuer, PVC PG, HA replicas
```

If a piece of behavior only exists in one of the two values files, it
needs a row in the divergence table above and a one-line justification.
That table is the contract; growing it without justification is a
manifesto violation.

## 5. Troubleshooting

| Symptom                                                | Likely cause                          | Fix                                                                |
| ------------------------------------------------------ | ------------------------------------- | ------------------------------------------------------------------ |
| `error: 'docker' not found`                            | Docker not installed                  | install Docker Desktop / `dockerd`                                 |
| `docker daemon is not reachable`                       | Docker Desktop stopped                | start it; `docker info` should succeed                             |
| `bind: address already in use` on :80 or :443          | another service holds the port        | stop it, or run with `K3D_HOST_PORT_HTTP=8080 …` (edit the script) |
| `failed to pull image rancher/k3s:…`                   | rate limit or offline                 | `docker login`, or pre-pull the image                              |
| `Error: no repositories configured` on `helm repo update` | first run, no repos yet              | re-run `make cluster-bootstrap`; the script adds them idempotently |
| `cert-manager` pods CrashLoopBackOff                   | CRDs not installed                    | the script passes `--set installCRDs=true`; reinstall              |
| `make cluster-up` says cluster exists but kubectl can't reach it | stale kubeconfig context     | `make cluster-reset`                                               |

## 6. Cross-references

- `CLAUDE.md` — the engineering rules this guide follows (dev/prod parity, one-command bootstrap).
- `MANIFESTO.md` — rationale.
- `docs/adr/0009-self-managed-k3s-on-hetzner.md` — the prod side of this
  contract (companion PR).
- `terraform/k8s/README.md` — the production cluster's Terraform module
  (later PR).
- `scripts/local-cluster.sh` — the script behind every `make cluster-*` target.
