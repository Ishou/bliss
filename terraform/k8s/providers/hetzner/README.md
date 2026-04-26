# Hetzner Cloud — k8s cluster module (v1)

First concrete implementation of the provider-agnostic cluster contract
declared at `terraform/k8s/`. See
[ADR-0009](../../../../docs/adr/0009-self-managed-k8s-deployment.md).

## Shape

- 1 control-plane node (`k3s server --cluster-init`).
- 1 worker node (`k3s agent`).
- One private network (`10.0.0.0/16`) with a `/24` subnet; nodes are
  dual-attached (public IPv4 + private IP).
- One firewall (SSH, k3s API, HTTP/HTTPS public; intra-network open).
- One `hcloud_ssh_key` per `var.ssh_public_keys` entry.
- An OpenTofu-generated `random_password` is the cluster join token,
  shared by both cloud-init templates — no post-apply SSH dance.

## Wiring

Invoked from `terraform/k8s/main.tf`. Authenticate via `HCLOUD_TOKEN`;
bring-up steps live in [`docs/deploy.md`](../../../../docs/deploy.md).

## v1 caveats

- **Single control plane** — accepted at v1 per ADR-0009 §10.
- **Kubeconfig is a documented one-time human step.** Contract outputs
  `kubeconfig`, `kubeconfig_path`, `cluster_ca_certificate` are `null`
  in v1; the maintainer fetches it via `scp` per `docs/deploy.md`.
- **Permissive firewall** — SSH/6443/80/443 open to `0.0.0.0/0`. A
  follow-up PR will introduce `var.maintainer_ssh_cidrs` + WireGuard.
- **No load balancer, no persistent volumes** — both follow-ups.

In-cluster operators (cert-manager, ingress-nginx, external-dns, CNPG)
are the next PR (step 3 of the ADR-0009 §8 migration).
