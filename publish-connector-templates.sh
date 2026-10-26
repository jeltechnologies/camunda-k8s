#!/usr/bin/env bash
# Publishes the standard connector templates to the Catalog in Camunda Hub (8.10 line only), by calling
# Keycunda's Catalog API as the first (admin) user with HTTP Basic, from inside the Keycunda pod
# (kubectl exec + the image's wget against localhost) - so nothing is exposed on the host. Needs
# DEMO_EMAIL and PASSWORD in the environment. Never fails the install: on any problem it prints a hint
# to use the Catalog page instead.
set -euo pipefail

: "${DEMO_EMAIL:?}" "${PASSWORD:?}"
BASE="http://localhost:8080/auth/admin/catalog"

microk8s kubectl rollout status deployment/keycunda -n camunda --timeout=300s >/dev/null 2>&1 || true

# The credentials travel on stdin (never in this script's own argv) and are only expanded inside the pod.
api() {
  local method="$1" path="$2" body="${3:-}"
  printf '%s' "${DEMO_EMAIL}:${PASSWORD}" | base64 -w0 | microk8s kubectl exec -i -n camunda deployment/keycunda -- \
    sh -c 'read -r auth; m="$1"; shift
           if [ "$m" = POST ]; then wget -q -T 120 -O - --header "Authorization: Basic $auth" --header "Content-Type: application/json" --post-data "$2" "$1"
           else wget -q -T 120 -O - --header "Authorization: Basic $auth" "$1"; fi' \
    sh "${method}" "${BASE}${path}" "${body}"
}

hint() {
  echo "WARNING: $1"
  echo "         Re-run ./publish-connector-templates.sh (needs DEMO_EMAIL and PASSWORD) once Camunda Hub is up."
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
