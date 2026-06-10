#!/bin/bash
set -euo pipefail
 
NAMESPACE="camunda"
 
echo "Finding camunda-connectors pod..."
POD=$(microk8s kubectl get pods -n "${NAMESPACE}" \
  --no-headers \
  -o custom-columns="NAME:.metadata.name" \
  | grep -i "connector" \
  | head -n 1)
 
if [ -z "${POD}" ]; then
  echo "ERROR: No connectors pod found in namespace '${NAMESPACE}'. Exiting."
  exit 1
fi
 
echo "Tailing logs for pod '${POD}'..."
echo ""
microk8s kubectl logs -n "${NAMESPACE}" -f "${POD}"
