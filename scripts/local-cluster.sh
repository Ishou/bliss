#!/usr/bin/env bash
# Local k3d cluster lifecycle for WordSparrow.
#
# Brings up a single k3d cluster ("wordsparrow-local") that mirrors the
# k3s contract we run on Hetzner in production (see ADR-0009). Same
# kubectl, same manifests, same operator set — minus a few documented
# divergences (no external-dns, self-signed cert issuer, ephemeral
# Postgres storage). See docs/local-development.md for the full list.
#
# Subcommands:
#   up          create the cluster if it doesn't already exist
#   down        delete the cluster
#   reset       down + up
#   bootstrap   helm-install ingress-nginx, cert-manager, CloudNative-PG
#   status      kubectl get nodes,pods -A against the local context
#
# This script never installs system packages. If a required CLI is
# missing it prints an install hint and exits non-zero.

set -euo pipefail

CLUSTER_NAME="wordsparrow-local"
KUBE_CONTEXT="k3d-${CLUSTER_NAME}"

# k3s image tag — pin to a stable v1.30.x release that matches what we
# intend to run in prod on Hetzner (ADR-0009). Update in lockstep with
# the prod Terraform module.
K3S_IMAGE="rancher/k3s:v1.30.6-k3s1"

# Helm chart versions — pinned so local stays reproducible. Bump
# deliberately in a follow-up PR; do not float to "latest".
INGRESS_NGINX_VERSION="4.11.3"   # ingress-nginx, K8s 1.30 compatible
CERT_MANAGER_VERSION="v1.16.1"   # cert-manager, supports K8s 1.30
CNPG_VERSION="0.22.1"            # CloudNative-PG operator chart

INGRESS_NGINX_REPO="https://kubernetes.github.io/ingress-nginx"
CERT_MANAGER_REPO="https://charts.jetstack.io"
CNPG_REPO="https://cloudnative-pg.github.io/charts"

log() { printf '[local-cluster] %s\n' "$*"; }
err() { printf '[local-cluster] error: %s\n' "$*" >&2; }

require_cmd() {
  local cmd="$1" hint="$2"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    err "'$cmd' not found on PATH. Install it first: $hint"
    exit 127
  fi
}

preflight() {
  require_cmd docker  "https://docs.docker.com/engine/install/"
  require_cmd k3d     "https://k3d.io/#installation (>= v5)"
  require_cmd kubectl "https://kubernetes.io/docs/tasks/tools/"
  require_cmd helm    "https://helm.sh/docs/intro/install/ (v3)"

  if ! docker info >/dev/null 2>&1; then
    err "docker daemon is not reachable. Start Docker Desktop / dockerd and retry."
    exit 1
  fi
}

cluster_exists() {
  # Parse the plain-text table (NAME is the first column) rather than JSON.
  # The JSON form is whitespace-sensitive — `grep "\"name\":\"x\""` silently
  # misses if k3d ever emits `"name": "x"` with a space after the colon.
  k3d cluster list 2>/dev/null \
    | awk 'NR>1 {print $1}' \
    | grep -qx "${CLUSTER_NAME}"
}

cmd_up() {
  preflight
  if cluster_exists; then
    log "cluster '${CLUSTER_NAME}' already exists; skipping create"
  else
    log "creating cluster '${CLUSTER_NAME}' (image ${K3S_IMAGE})"
    # 1 server + 1 agent mirrors the smallest prod shape. Ports 80/443
    # are mapped through the k3d load balancer so ingress-nginx is
    # reachable on http(s)://wordsparrow.local once /etc/hosts points
    # the name at 127.0.0.1.
    k3d cluster create "${CLUSTER_NAME}" \
      --image "${K3S_IMAGE}" \
      --servers 1 \
      --agents 1 \
      --port "80:80@loadbalancer" \
      --port "443:443@loadbalancer" \
      --k3s-arg "--disable=traefik@server:*" \
      --wait
  fi
  kubectl config use-context "${KUBE_CONTEXT}" >/dev/null
  log "kubectl context set to ${KUBE_CONTEXT}"
  log "next: make cluster-bootstrap"
}

cmd_down() {
  # Run the full preflight so a stopped Docker daemon yields a clear
  # error rather than the misleading "cluster does not exist; nothing
  # to do" path (cluster_exists swallows stderr).
  preflight
  if cluster_exists; then
    log "deleting cluster '${CLUSTER_NAME}'"
    k3d cluster delete "${CLUSTER_NAME}"
  else
    log "cluster '${CLUSTER_NAME}' does not exist; nothing to do"
  fi
}

cmd_reset() {
  cmd_down
  cmd_up
}

helm_repo_add_idempotent() {
  local name="$1" url="$2"
  # Plain-text table parsing (NAME is the first column). Same rationale as
  # cluster_exists: avoid whitespace-fragile JSON grepping, and avoid taking
  # a hard `jq` dependency that isn't in the preflight require_cmd list.
  if ! helm repo list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "${name}"; then
    helm repo add "${name}" "${url}" >/dev/null
  fi
}

cmd_bootstrap() {
  preflight
  if ! cluster_exists; then
    err "cluster '${CLUSTER_NAME}' is not running. Run: make cluster-up"
    exit 1
  fi
  kubectl config use-context "${KUBE_CONTEXT}" >/dev/null

  log "adding helm repos"
  helm_repo_add_idempotent ingress-nginx   "${INGRESS_NGINX_REPO}"
  helm_repo_add_idempotent jetstack        "${CERT_MANAGER_REPO}"
  helm_repo_add_idempotent cnpg            "${CNPG_REPO}"
  helm repo update >/dev/null

  log "installing ingress-nginx ${INGRESS_NGINX_VERSION}"
  helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
    --version "${INGRESS_NGINX_VERSION}" \
    --namespace ingress-nginx --create-namespace \
    --set controller.publishService.enabled=true \
    --wait

  log "installing cert-manager ${CERT_MANAGER_VERSION} (self-signed locally)"
  helm upgrade --install cert-manager jetstack/cert-manager \
    --version "${CERT_MANAGER_VERSION}" \
    --namespace cert-manager --create-namespace \
    --set crds.enabled=true \
    --wait

  log "installing cloudnative-pg operator ${CNPG_VERSION}"
  helm upgrade --install cnpg cnpg/cloudnative-pg \
    --version "${CNPG_VERSION}" \
    --namespace cnpg-system --create-namespace \
    --wait

  log "bootstrap complete. external-dns is intentionally NOT installed locally;"
  log "see docs/local-development.md for the prod-divergence rationale."
}

cmd_status() {
  require_cmd kubectl "https://kubernetes.io/docs/tasks/tools/"
  kubectl --context "${KUBE_CONTEXT}" get nodes,pods -A
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") <up|down|reset|bootstrap|status>

  up         create the k3d cluster '${CLUSTER_NAME}' (idempotent)
  down       delete the k3d cluster
  reset      delete then recreate
  bootstrap  helm-install ingress-nginx, cert-manager, cloudnative-pg
  status     kubectl get nodes,pods -A
USAGE
}

main() {
  if [ $# -lt 1 ]; then
    usage >&2
    exit 2
  fi
  case "$1" in
    up)        cmd_up ;;
    down)      cmd_down ;;
    reset)     cmd_reset ;;
    bootstrap) cmd_bootstrap ;;
    status)    cmd_status ;;
    -h|--help|help) usage ;;
    *) usage >&2; exit 2 ;;
  esac
}

main "$@"
