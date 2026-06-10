#!/bin/bash
set -euo pipefail

NAMESPACE="camunda"
SECRETS_FILE="connector-secrets.yaml"

if [ ! -f "${SECRETS_FILE}" ]; then
  echo "No ${SECRETS_FILE} found, skipping connector secrets update."
  exit 0
fi

echo "Secrets to be applied:"
awk '/^(data|stringData):/{in_data=1; next}
     in_data && /^[^[:space:]]/{in_data=0}
     in_data && /^[[:space:]]/{
       line=$0
       sub(/^[[:space:]]+/, "", line)
       sub(/:.*/, "", line)
       if (line != "") print "  " line " = *******"
     }' "${SECRETS_FILE}"
echo ""

SECRET_NAME=$(awk '/^name:/{print $2; exit} /^  name:/{print $2; exit}' "${SECRETS_FILE}")
if [ -z "${SECRET_NAME}" ]; then
  SECRET_NAME="camunda-connector-secrets"
  echo "Could not detect secret name from file, defaulting to '${SECRET_NAME}'."
fi

echo "Checking for existing secret '${SECRET_NAME}' in namespace '${NAMESPACE}'..."
if microk8s kubectl get secret "${SECRET_NAME}" -n "${NAMESPACE}" &>/dev/null; then
  echo "Deleting existing secret '${SECRET_NAME}'..."
  microk8s kubectl delete secret "${SECRET_NAME}" -n "${NAMESPACE}"
  echo "Existing secret deleted."
else
  echo "No existing secret found, skipping deletion."
fi
echo ""

echo "Applying connector secrets from '${SECRETS_FILE}' to namespace '${NAMESPACE}'..."
microk8s kubectl apply -f "${SECRETS_FILE}" -n "${NAMESPACE}"
echo "Secrets applied."
echo ""

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
echo "Found pod: ${POD}"
echo ""

echo "Patching deployment to mount secrets..."
DEPLOYMENT=$(microk8s kubectl get deployments -n "${NAMESPACE}" \
  --no-headers \
  -o custom-columns="NAME:.metadata.name" \
  | grep -i "connector" \
  | head -n 1)
microk8s kubectl patch deployment "${DEPLOYMENT}" -n "${NAMESPACE}" --type=json -p='[
  {
    "op": "add",
    "path": "/spec/template/spec/containers/0/envFrom/-",
    "value": {
      "secretRef": {
        "name": "camunda-connector-secrets"
      }
    }
  }
]'
echo "Deployment '${DEPLOYMENT}' patched."
echo ""

echo "Restarting pod '${POD}'..."
microk8s kubectl delete pod "${POD}" -n "${NAMESPACE}"
echo ""

echo "Waiting for rollout to complete..."
microk8s kubectl rollout status deployment/"${DEPLOYMENT}" \
  -n "${NAMESPACE}" \
  --timeout=120s
echo ""

echo "Done. Connector secrets updated and connectors pod restarted successfully."
