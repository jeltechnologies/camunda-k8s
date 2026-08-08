#!/usr/bin/env bash
set -euo pipefail

echo "=================================================================="
echo "WARNING: This script completely removes MicroK8s, Helm and every"
echo "other change made by 1-install-microk8s.sh, including the whole"
echo "Kubernetes cluster and ALL data in it (Camunda included, if still"
echo "installed)."
echo "=================================================================="
echo ""
echo "Run this when you need to change this machine's static IP address"
echo "or hostname: MicroK8s bakes both into its cluster certificates at"
echo "install time and has no supported way to change them afterwards."
echo "The fix is to remove MicroK8s entirely, change the IP/hostname at"
echo "the OS level, then run 1-install-microk8s.sh again from scratch."
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

# Deliberately not calling uninstall-camunda-microk8s.sh here: it deletes
# Helm releases/namespaces/PVs one resource at a time via `microk8s kubectl`,
# which either hangs or (worse, under `set -euo pipefail`, since several of
# its delete calls have no `|| true`) aborts partway through if the API
# server isn't reachable — exactly the state this script exists to recover
# from (a broken cluster after an IP/hostname change). `snap remove --purge`
# below wipes the entire cluster regardless of API health, making all of
# that redundant anyway. Only the OS-level artifacts Camunda leaves outside
# the cluster — which `snap remove` does NOT touch — need handling here,
# and they don't depend on the cluster being reachable at all.
echo "=================================================================="
echo "Removing Camunda's host-level artifacts (if any)"
echo "=================================================================="
if [[ -f ./install-env.sh ]]; then
  source ./install-env.sh
else
  CAMUNDA_DOMAIN="${CAMUNDA_DOMAIN:-camunda.local}"
  ZEEBE_DOMAIN="${ZEEBE_DOMAIN:-zeebe.camunda.local}"
fi

# ~/camunda-docs and ~/camunda-connectors are deliberately left in place —
# they're the user's own documents and custom connector JARs, not generated
# state. 2-install-camunda-microk8s.sh recreates the PVs pointing at these
# same paths on every install, so whatever's already here gets automatically
# redeployed on the next install rather than having to be re-added by hand.
[[ -d ~/camunda-docs ]] && echo "~/camunda-docs left in place" || true
[[ -d ~/camunda-connectors ]] && echo "~/camunda-connectors left in place" || true

HOSTS_FILE="/etc/hosts"
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
    echo "Removed from /etc/hosts: ${entry}"
  fi
done

echo "=================================================================="
echo "Removing MicroK8s (snap remove --purge)"
echo "=================================================================="
# --purge wipes /var/snap/microk8s entirely (certs, etcd/dqlite data, the
# lot), so the next install generates brand new cluster certificates
# against whatever IP/hostname the machine has at that point.
sudo snap remove microk8s --purge 2>/dev/null || echo "MicroK8s not installed or already removed"

echo "=================================================================="
echo "Removing Helm"
echo "=================================================================="
sudo rm -f /usr/local/bin/helm

echo "=================================================================="
echo "Removing kubectl configuration"
echo "=================================================================="
rm -f ~/.kube/config
rmdir ~/.kube 2>/dev/null || true
sed -i '\#alias kubectl="microk8s kubectl"#d' ~/.bashrc
sed -i '\#export KUBECONFIG=~/.kube/config#d' ~/.bashrc

echo "=================================================================="
echo "Removing jelte from the microk8s group"
echo "=================================================================="
sudo deluser "${USER}" microk8s 2>/dev/null || echo "User not in microk8s group or group already removed"

echo "=================================================================="
echo "Re-enabling swap"
echo "=================================================================="
sudo sed -i '/ swap / s/^#\(.*\)$/\1/' /etc/fstab
sudo swapon -a 2>/dev/null || echo "Nothing to swap on (no swap file/partition configured)"

echo ""
echo "=================================================================="
echo "Cleanup complete!"
echo "=================================================================="
echo ""
echo "The system is back to its pre-1-install-microk8s.sh state."
echo "If you're changing this machine's IP address or hostname, do that"
echo "now (and reboot if needed) before running 1-install-microk8s.sh again."
