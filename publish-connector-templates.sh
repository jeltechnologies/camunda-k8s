#!/usr/bin/env bash
# Publishes the standard connector templates to the Catalog in Camunda Hub (8.10 line only), by calling
# Keycunda's Catalog API as the first (admin) user with HTTP Basic. Needs DEMO_EMAIL and PASSWORD in the
# environment. Never fails the install: on any problem it prints a hint to use the Catalog page instead.
set -euo pipefail

: "${DEMO_EMAIL:?}" "${PASSWORD:?}"
LOCAL_PORT=18080
BASE="http://localhost:${LOCAL_PORT}/auth/admin/catalog"

microk8s kubectl port-forward -n camunda svc/keycunda "${LOCAL_PORT}:80" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "${PF_PID}" 2>/dev/null || true' EXIT

# Credentials go through stdin (curl -K -), never onto a command line.
api() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS --max-time 120 -X "${method}" -H 'Content-Type: application/json' "${BASE}${path}")
  [[ -n "${body}" ]] && args+=(-d "${body}")
  printf 'user = "%s:%s"\n' "${DEMO_EMAIL}" "${PASSWORD}" | curl -K - -f "${args[@]}"
}

hint() {
  echo "WARNING: $1"
  echo "         Publish them manually: open Catalog at https://${CAMUNDA_DOMAIN:-<your-domain>}/keycunda/catalog"
  exit 0
}

PLAN=""
for attempt in $(seq 1 30); do
  PLAN="$(api POST /plan '{"mode":"all"}' 2>&1 || true)"
  [[ "${PLAN}" == *'"needsConfirmation"'* ]] && break
  echo "  waiting for the Catalog API (attempt ${attempt}/30): ${PLAN:0:160}"
  sleep 5
done
[[ "${PLAN}" == *'"needsConfirmation"'* ]] || hint "Keycunda's Catalog API did not give a plan (last answer: ${PLAN:-none})."
[[ "${PLAN}" == *'"needsConfirmation":false'* ]] || hint "Publishing would replace or remove existing Catalog assets, so it was skipped."

api POST /publish '{"mode":"all"}' >/dev/null || hint "Could not start publishing."
for _ in $(seq 1 120); do
  STATUS="$(api GET /publish-status.json 2>/dev/null || true)"
  case "${STATUS}" in
    *'"SUCCESS"'*) echo "${STATUS}"; exit 0 ;;
    *'"ERROR"'*)   hint "Publishing failed: ${STATUS}" ;;
  esac
  sleep 5
done
hint "Publishing did not finish in time."
