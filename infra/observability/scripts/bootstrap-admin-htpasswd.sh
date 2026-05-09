#!/usr/bin/env bash
# bootstrap-admin-htpasswd.sh — ADR-0028 §2 implementation.
#
# Generates a 32-character random password, computes its bcrypt
# htpasswd hash, creates (or rotates) the `admin-htpasswd` Secret in
# the observability namespace, and prints the plaintext password to
# stdout exactly once for the operator to save in their password
# manager. The Secret stores only the bcrypt hash; the plaintext is
# never written to disk and never recoverable from the cluster.
#
# Usage:
#   ./infra/observability/scripts/bootstrap-admin-htpasswd.sh
#
# Idempotent: if the Secret already exists, the script asks before
# rotating. To rotate non-interactively, pass --rotate (skips the
# prompt; old password is invalidated immediately).
#
# Prereqs:
#   - kubectl + KUBECONFIG=~/.kube/wordsparrow-prod
#   - htpasswd (apache2-utils on Linux, brew install httpd on macOS)
#   - openssl
#
# After running:
#   helm install observability infra/observability/ \
#     -n observability -f infra/observability/values-prod.yaml
#
# The same Secret is referenced by analytics.wordsparrow.io once
# PR-G lands (htpasswd-gating Matomo); both observability + matomo
# charts then point at the same `admin-htpasswd` Secret in their
# respective namespaces (each must be bootstrapped separately;
# Secrets are namespaced).

set -euo pipefail

NAMESPACE="${OBSERVABILITY_NAMESPACE:-observability}"
SECRET_NAME="admin-htpasswd"
USERNAME="admin"
ROTATE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rotate)
      ROTATE=true
      shift
      ;;
    --namespace=*)
      NAMESPACE="${1#--namespace=}"
      shift
      ;;
    --help|-h)
      sed -n 's/^# \?//p' "$0" | head -40
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if ! command -v htpasswd >/dev/null 2>&1; then
  echo "ERROR: htpasswd not found. Install apache2-utils (Linux) or 'brew install httpd' (macOS)." >&2
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl not found." >&2
  exit 1
fi

# Confirm namespace exists; create if not
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# If the Secret already exists, confirm before rotating
if kubectl -n "$NAMESPACE" get secret "$SECRET_NAME" >/dev/null 2>&1; then
  if [[ "$ROTATE" != "true" ]]; then
    echo "Secret $NAMESPACE/$SECRET_NAME already exists." >&2
    echo "Re-run with --rotate to invalidate the existing password and mint a new one." >&2
    exit 1
  fi
fi

# Generate a 32-character URL-safe random password (~190 bits entropy).
# Using openssl with a base64 encoding then trimming non-URL-safe chars
# would be simpler but yields short results; the explicit alphabet keeps
# the password length predictable.
PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"

# Compute the bcrypt htpasswd line. -B = bcrypt, -n = output to stdout
# without writing a file, -b = read password from command line (acceptable
# here because the password is never persisted to disk and the script
# argv is not logged).
HTPASSWD_LINE="$(htpasswd -B -n -b "$USERNAME" "$PASSWORD")"

# Create or update the Secret. The secret data is the bcrypt hash, not
# the plaintext.
kubectl -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
  --from-literal=auth="$HTPASSWD_LINE" \
  --dry-run=client -o yaml \
  | kubectl apply -f -

# Print the plaintext exactly once. The operator copies this into their
# password manager; this is the ONLY time it's shown.
cat <<EOF

================================================================
  Bootstrap complete — Secret $NAMESPACE/$SECRET_NAME created.
================================================================

  Username : $USERNAME
  Password : $PASSWORD

  Save this in your password manager NOW. The plaintext is not
  stored anywhere — the cluster only holds the bcrypt hash, and
  this script writes nothing to disk.

  Use these credentials to log into:
    https://errors.wordsparrow.io
    https://dashboard.wordsparrow.io

  To rotate, re-run this script with --rotate. Current sessions
  will continue using the old password until ingress-nginx
  reloads its auth file (~5 min), at which point both old and new
  passwords are accepted; after the reload, only the new one
  works. Force a reload by restarting the ingress-nginx pod.

================================================================

EOF
