#!/usr/bin/env bash
# Idempotently apply every *.json rule in this directory to SigNoz (schema: v0.122).
set -euo pipefail

: "${SIGNOZ_URL:?SIGNOZ_URL must be set (e.g. https://errors.wordsparrow.io)}"
: "${SIGNOZ_API_KEY:?SIGNOZ_API_KEY must be set}"

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTH="Authorization: SIGNOZ-API-KEY ${SIGNOZ_API_KEY}"
CT="Content-Type: application/json"

list_rules() {
  curl -fsS -H "$AUTH" "${SIGNOZ_URL%/}/api/v1/rules"
}

for f in "$DIR"/*.json; do
  name="$(jq -r .alert "$f")"
  id="$(list_rules | jq -r --arg n "$name" '.data.rules[]? | select(.alert==$n) | .id' | head -n1)"
  if [[ -n "$id" && "$id" != "null" ]]; then
    code="$(curl -sS -o /tmp/signoz-resp -w '%{http_code}' -X PUT \
      -H "$AUTH" -H "$CT" --data-binary "@$f" \
      "${SIGNOZ_URL%/}/api/v1/rules/${id}")"
    op="PUT id=$id"
  else
    code="$(curl -sS -o /tmp/signoz-resp -w '%{http_code}' -X POST \
      -H "$AUTH" -H "$CT" --data-binary "@$f" \
      "${SIGNOZ_URL%/}/api/v1/rules")"
    op="POST"
  fi
  if [[ "$code" =~ ^2 ]]; then
    echo "ok  ${name}  (${op} -> ${code})"
  else
    echo "FAIL ${name}  (${op} -> ${code}): $(cat /tmp/signoz-resp)" >&2
    exit 1
  fi
done
