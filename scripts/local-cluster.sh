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
INGRESS_NGINX_VERSION="4.15.1"   # ingress-nginx, K8s 1.30 compatible
CERT_MANAGER_VERSION="v1.20.2"   # cert-manager, supports K8s 1.30
CNPG_VERSION="0.28.0"            # CloudNative-PG operator chart

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

  log "creating self-signed ClusterIssuer (local TLS)"
  kubectl apply --context "${KUBE_CONTEXT}" -f - <<'ISSUER'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: selfsigned
spec:
  selfSigned: {}
ISSUER

  log "bootstrap complete. external-dns is intentionally NOT installed locally;"
  log "see docs/local-development.md for the prod-divergence rationale."
}

APP_IMAGE="ghcr.io/ishou/bliss/wordsparrow-api"
APP_TAG="local"
CHART_DIR="grid/api/deploy/chart"
APP_NAMESPACE="default"
APP_RELEASE="wordsparrow-api"

cmd_deploy() {
  preflight
  if ! cluster_exists; then
    err "cluster '${CLUSTER_NAME}' is not running. Run: make cluster-up && make cluster-bootstrap"
    exit 1
  fi
  kubectl config use-context "${KUBE_CONTEXT}" >/dev/null

  # Resolve repo root (script may be called from anywhere).
  local repo_root
  repo_root="$(cd "$(dirname "$0")/.." && pwd)"
  local chart="${repo_root}/${CHART_DIR}"

  log "building Docker image ${APP_IMAGE}:${APP_TAG}"
  docker build -t "${APP_IMAGE}:${APP_TAG}" -f "${repo_root}/grid/api/Dockerfile" "${repo_root}"

  log "importing image into k3d cluster '${CLUSTER_NAME}'"
  k3d image import "${APP_IMAGE}:${APP_TAG}" --cluster "${CLUSTER_NAME}"

  log "installing/upgrading Helm release '${APP_RELEASE}'"
  helm upgrade --install "${APP_RELEASE}" "${chart}" \
    -n "${APP_NAMESPACE}" \
    -f "${chart}/values-local.yaml" \
    --set "image.tag=${APP_TAG}" \
    --set "image.pullPolicy=Never" \
    --wait --timeout 120s

  log "deploy complete — https://wordsparrow.local/ (self-signed cert)"
}

cmd_dev() {
  require_cmd pnpm "https://pnpm.io/installation (>= v10)"

  # `--force` kills any process already bound to one of the dev ports.
  # Without it the script fails-fast with the offending PIDs so the user
  # can decide (a stray gradle from a crashed prior run vs. a real
  # service they care about elsewhere). Surfaces the "Address already
  # in use" Ktor exception before it happens — the Gradle wrapper buries
  # that error several lines deep otherwise.
  local force=0
  for arg in "$@"; do
    case "$arg" in
      --force|-f) force=1 ;;
      *) log "unknown dev flag: $arg"; exit 2 ;;
    esac
  done

  local -a dev_ports=(7777 7778 5173)
  local p stray_found=0
  for p in "${dev_ports[@]}"; do
    local pids
    pids=$(lsof -ti "tcp:$p" -sTCP:LISTEN 2>/dev/null || true)
    if [ -n "$pids" ]; then
      stray_found=1
      if [ "$force" = "1" ]; then
        log "port $p in use by pid(s) ${pids//$'\n'/, } — killing (--force)"
        # shellcheck disable=SC2086
        kill -9 $pids 2>/dev/null || true
      else
        log "port $p in use by pid(s) ${pids//$'\n'/, }"
      fi
    fi
  done
  if [ "$stray_found" = "1" ] && [ "$force" != "1" ]; then
    log "rerun with: make dev FORCE=1   (or scripts/local-cluster.sh dev --force)"
    exit 1
  fi
  # Brief wait so the kernel finishes releasing the sockets before the
  # Ktor servers attempt to bind below.
  if [ "$force" = "1" ]; then sleep 1; fi

  local repo_root
  repo_root="$(cd "$(dirname "$0")/.." && pwd)"

  if [ ! -d "${repo_root}/frontend/node_modules" ]; then
    log "frontend/node_modules missing — running pnpm install"
    pnpm --dir "${repo_root}/frontend" install
  fi

  log "starting full-stack dev (Ctrl+C to stop all)"
  log "  Grid API: http://localhost:7777  (Ktor auto-reload)"
  log "  Game API: http://localhost:7778  (Ktor auto-reload, WebSocket on the same host)"
  log "  Frontend: http://localhost:5173  (Vite HMR)"

  # Kill all child processes on exit (Ctrl+C, term, or script error).
  # shellcheck disable=SC2064
  trap "trap - EXIT; kill 0 2>/dev/null" EXIT INT TERM

  # 1. Continuous compilation — one watcher per Ktor module so a save
  #    in either bounded context triggers a re-run of just that one.
  "${repo_root}/gradlew" -t :grid:api:classes -p "${repo_root}" --no-daemon -q &
  "${repo_root}/gradlew" -t :game:api:classes -p "${repo_root}" --no-daemon -q &

  # 2. Ktor servers with development=true (auto-reload on class changes).
  #    Each defaults to its module's DEFAULT_PORT (grid 7777, game 7778);
  #    prod charts pin PORT explicitly via `env:` so the local defaults
  #    don't bleed into the cluster.
  "${repo_root}/gradlew" :grid:api:run -p "${repo_root}" --no-daemon -q &
  "${repo_root}/gradlew" :game:api:run -p "${repo_root}" --no-daemon -q &

  # 3. Vite dev server with HMR. `frontend/.env.development` points at
  #    localhost:7777 (grid) and localhost:7778 (game) — no MSW.
  pnpm --dir "${repo_root}/frontend" dev &

  wait
}

cmd_status() {
  require_cmd kubectl "https://kubernetes.io/docs/tasks/tools/"
  kubectl --context "${KUBE_CONTEXT}" get nodes,pods -A
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") <up|down|reset|bootstrap|deploy|dev|status>

  up         create the k3d cluster '${CLUSTER_NAME}' (idempotent)
  down       delete the k3d cluster
  reset      delete then recreate
  bootstrap  helm-install ingress-nginx, cert-manager, cloudnative-pg
  deploy     build grid-api image, import into k3d, helm install the app
  dev [-f]   start API (hot reload) + frontend (Vite HMR) on the host
             (-f / --force: kill any stray process on 7777/7778/5173)
  status     kubectl get nodes,pods -A
USAGE
}

main() {
  if [ $# -lt 1 ]; then
    usage >&2
    exit 2
  fi
  local subcmd="$1"
  shift
  case "$subcmd" in
    up)        cmd_up ;;
    down)      cmd_down ;;
    reset)     cmd_reset ;;
    bootstrap) cmd_bootstrap ;;
    deploy)    cmd_deploy ;;
    dev)       cmd_dev "$@" ;;
    status)    cmd_status ;;
    -h|--help|help) usage ;;
    *) usage >&2; exit 2 ;;
  esac
}

main "$@"
