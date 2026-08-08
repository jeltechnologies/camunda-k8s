#!/usr/bin/env bash
set -euo pipefail

echo "=================================================================="
echo "WARNING: This script will completely remove all Camunda components"
echo "and data installed by 2-install-camunda-microk8s.sh"
echo "=================================================================="
echo ""
read -p "Are you sure you want to continue? Type 'yes' to proceed: " -r CONFIRM
if [[ "${CONFIRM}" != "yes" ]]; then
  echo "Aborted."
  exit 0
fi

echo ""
echo "Sudo access"
echo "=================================================================="
# Prompt for the sudo password up front so uninstall doesn't stall
sudo echo "Please provide your sudo password"

# Source the environment if it exists
if [[ -f ./install-env.sh ]]; then
  source ./install-env.sh
else
  echo "Warning: install-env.sh not found. Using defaults for cleanup."
  CAMUNDA_DOMAIN="${CAMUNDA_DOMAIN:-camunda.localhost}"
  ZEEBE_DOMAIN="${ZEEBE_DOMAIN:-zeebe.localhost}"
fi

echo "=================================================================="
echo "Uninstalling Camunda Helm release"
echo "=================================================================="
helm uninstall camunda -n camunda 2>/dev/null || echo "Helm release not found or already removed"

# Everything else this script used to delete resource-by-resource (Keycunda,
# ingresses, the Elasticsearch/PostgreSQL StatefulSets, their PVCs, secrets,
# RBAC, ...) is namespace-scoped to "camunda", so deleting the namespace
# removes all of it in one go. Deleting a PVC individually right after its
# StatefulSet blocks in the foreground until the pod that was using it
# finishes terminating (kubernetes.io/pvc-protection finalizer) - with no
# timeout, that can hang indefinitely if the pod is slow or stuck. Deleting
# the namespace instead avoids that ordering problem entirely.
echo "=================================================================="
echo "Deleting camunda namespace (this removes all namespaced resources)"
echo "=================================================================="
microk8s kubectl delete namespace camunda --ignore-not-found --wait=false

echo "Waiting for namespace deletion (up to 5 minutes)..."
NS_DELETED=false
for i in {1..300}; do
  if ! microk8s kubectl get namespace camunda &>/dev/null; then
    echo "Namespace deleted successfully"
    NS_DELETED=true
    break
  fi
  sleep 1
done

if [[ "${NS_DELETED}" != "true" ]]; then
  echo "Namespace still terminating after 5 minutes - likely stuck on a finalizer."
  echo "Forcing removal by clearing the namespace's finalizers..."
  microk8s kubectl patch namespace camunda --type=merge --subresource=finalize -p '{"spec":{"finalizers":[]}}' || true
  for i in {1..30}; do
    if ! microk8s kubectl get namespace camunda &>/dev/null; then
      echo "Namespace deleted successfully"
      NS_DELETED=true
      break
    fi
    sleep 1
  done
fi

if [[ "${NS_DELETED}" != "true" ]]; then
  echo "Warning: camunda namespace is still present. Check 'microk8s kubectl get namespace camunda -o yaml' for stuck resources."
fi

# PVs are cluster-scoped, not part of the namespace, so they survive the
# delete above (they'd just go Released). Deleting them now - after the
# namespace, and therefore after their claims - is also what avoids the
# analogous kubernetes.io/pv-protection finalizer hang.
echo "=================================================================="
echo "Deleting persistent volumes"
echo "=================================================================="
microk8s kubectl delete pv camunda-docs-pv --ignore-not-found
microk8s kubectl delete pv camunda-connectors-pv --ignore-not-found

echo "=================================================================="
echo "Deleting ConfigMap in the ingress namespace"
echo "=================================================================="
microk8s kubectl delete configmap nginx-ingress-tcp-microk8s-conf --namespace ingress --ignore-not-found

echo "=================================================================="
echo "Keeping ~/camunda-docs and ~/camunda-connectors"
echo "=================================================================="
# Deliberately not deleted: these are the user's own documents and custom
# connector JARs, not generated state. 2-install-camunda-microk8s.sh
# recreates the PVs pointing at these same paths on every install, so
# whatever's already here gets automatically redeployed on the next
# install rather than having to be re-added by hand.
[[ -d ~/camunda-docs ]] && echo "~/camunda-docs left in place" || true
[[ -d ~/camunda-connectors ]] && echo "~/camunda-connectors left in place" || true

echo "=================================================================="
echo "Removing /etc/hosts entries"
echo "=================================================================="
# Remove the /etc/hosts entries that were added during installation
HOSTS_FILE="/etc/hosts"

# Determine which entries were added
if [[ "${BEHIND_REVERSE_PROXY:-false}" == "true" ]]; then
  ENTRIES_TO_REMOVE=(
      "127.0.0.1 camunda.local"
      "127.0.0.1 zeebe.camunda.local"
  )
else
  ENTRIES_TO_REMOVE=(
      "127.0.0.1 camunda.local"
      "127.0.0.1 zeebe.camunda.local"
      "127.0.0.1 ${CAMUNDA_DOMAIN}"
      "127.0.0.1 ${ZEEBE_DOMAIN}"
  )
fi

for entry in "${ENTRIES_TO_REMOVE[@]}"; do
  if grep -qF "${entry}" "${HOSTS_FILE}"; then
    sudo sed -i "/^$(echo "${entry}" | sed 's/[[\.*^$/]/\\&/g')$/d" "${HOSTS_FILE}"
    echo "Removed: ${entry}"
  fi
done

echo "=================================================================="
echo "Cleanup complete!"
echo "=================================================================="
echo ""
echo "The system is now in the state after running 1-install-microk8s.sh"
echo "You can run 2-install-camunda-microk8s.sh again to reinstall."
