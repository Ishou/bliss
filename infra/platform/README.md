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

## ObjectStore CR — out of scope for v1

CloudNativePG's cluster-scoped `ObjectStore` CRD landed with the
CNPG-i / Barman Cloud plugin split (CNPG ~v1.26+); the 0.22.1
operator chart pinned in `Chart.yaml` predates that split, so the
right shape today is the inline `.spec.backup.barmanObjectStore`
on the WordSparrow Cluster CR — already wired in
`grid/api/deploy/chart/templates/postgres-cluster.yaml`. The
operator-config workstream owns the migration PR once the chart
picks up the plugin.

<!--
apiVersion: barmancloud.cnpg.io/v1
kind: ObjectStore
metadata:
  name: bliss-cnpg-backups
  labels:
    {{- include "platform.labels" . | nindent 4 }}
spec:
  configuration:
    destinationPath: s3://bliss-cnpg-backups/
    endpointURL: https://fsn1.your-objectstorage.com
    s3Credentials:
      accessKeyId:
        name: hcloud-os-credentials
        key: ACCESS_KEY_ID
      secretAccessKey:
        name: hcloud-os-credentials
        key: SECRET_ACCESS_KEY
    wal:
      compression: gzip
-->

See `docs/deploy.md` "Platform operators bootstrap" for the durable
maintainer recipe.
