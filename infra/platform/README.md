# Platform operators umbrella chart

Bootstraps the five operators required by ADR-0009 §3 (+ ADR-0010 §5)
into a fresh k3s cluster: **cert-manager**, **ingress-nginx**,
**external-dns**, **CloudNativePG**, **hcloud-csi**.

Assumes k3s 1.30.x (PR52 / `terraform/k8s/`) and a kubeconfig pointing
at the right context (`~/.kube/wordsparrow-prod` for prod).

## Prerequisite secrets (one-time, ADR-0009 §10)

Must exist before `helm install` — never committed:

```sh
kubectl create namespace platform
kubectl -n platform create secret generic cloudflare-api-token \
  --from-literal=cloudflare_api_token="$CLOUDFLARE_API_TOKEN_DNS"
kubectl -n kube-system create secret generic hcloud-csi-token \
  --from-literal=token="$HCLOUD_TOKEN"
```

App-level secrets (`wordsparrow-api-env`) ship with the WordSparrow
chart in step 4 of the ADR-0009 §8 migration — not here.

## Install

```sh
helm dep update infra/platform/
helm install platform infra/platform/ \
  -n platform --create-namespace \
  -f infra/platform/values-prod.yaml \
  --set clusterIssuer.letsencrypt.email="<your-email>"
```

Verify: `kubectl get pods -A`, `kubectl get clusterissuer letsencrypt-prod`,
`kubectl get crd | grep cnpg.io`, `kubectl get sc hcloud-volumes`.

## Local (k3d)

```sh
helm install platform infra/platform/ -n platform --create-namespace \
  -f infra/platform/values-local.yaml
```

Local skips external-dns and hcloud-csi; renders a `selfsigned`
ClusterIssuer matching the WordSparrow chart's `values-local.yaml`.

## Trade-offs

- **No Hetzner Cloud Controller Manager at v1.** ingress-nginx runs
  `hostNetwork: true` with a ClusterIP Service — binds directly to the
  node's :80/:443. Paying for a Hetzner LB is a follow-up ADR.
- **CNPG backup ObjectStore stays commented out.** Backup wiring lives
  inline on the WordSparrow Cluster CR until CNPG-i lands; see
  `templates/cnpg-backup-objectstore.yaml`.

See `docs/deploy.md` "Platform operators bootstrap" for the durable
maintainer recipe.
