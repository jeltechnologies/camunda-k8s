#!/usr/bin/env bash

set -euo pipefail

UBUNTU_VERSION=$(. /etc/os-release && echo "${VERSION_ID}")
if [[ "${UBUNTU_VERSION}" != "24.04" && "${UBUNTU_VERSION}" != "26.04" ]]; then
  echo "WARNING: This script is tested on Ubuntu 24.04 and 26.04."
  echo "         Detected: Ubuntu ${UBUNTU_VERSION}. Proceeding anyway..."
fi
echo "Ubuntu version: ${UBUNTU_VERSION}"

echo ============================================================
echo Updating operating system
echo ============================================================
sudo apt update
sudo apt full-upgrade -y
sudo apt install -y htop curl wget git

echo ============================================================
echo Disabling swap - required for Kubernetes
echo ============================================================
sudo swapoff -a
sudo sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab

echo ============================================================
echo Installing MicroK8s
echo ============================================================
echo ""
echo Please wait while MicroK8s gets installed...
echo ""
sudo snap install microk8s --classic --channel=1.32/stable

echo ============================================================
echo Disabling snap auto-updates for MicroK8s
echo ============================================================
sudo snap refresh --hold=forever microk8s
echo "Snap auto-updates disabled for MicroK8s"

echo ============================================================
echo Configuring user permissions
echo ============================================================
sudo usermod -aG microk8s $USER
sudo chown -R $USER ~/.kube 2>/dev/null || true
mkdir -p ~/.kube

echo ============================================================
echo Waiting for MicroK8s to be ready
echo ============================================================
echo "Waiting for MicroK8s services to start..."
for i in 1 2 3 4 5; do
  sudo microk8s status --wait-ready --timeout 30 && break || true
  echo "Not ready yet, retrying in 15 seconds (attempt $i/5)..."
  sleep 15
done
sudo microk8s status --wait-ready --timeout 60 || true

echo ============================================================
echo Enabling required addons
echo ============================================================
sudo microk8s enable hostpath-storage || true
sudo microk8s enable ingress || true
sudo microk8s enable metrics-server || true

echo "Waiting for addons to be ready..."
sudo microk8s status --wait-ready --timeout 120 || true

echo ============================================================
echo Configuring kubectl
echo ============================================================
sudo microk8s config > ~/.kube/config
chmod 600 ~/.kube/config

if ! grep -q 'microk8s kubectl' ~/.bashrc; then
  echo 'alias kubectl="microk8s kubectl"' >> ~/.bashrc
fi
if ! grep -q 'KUBECONFIG' ~/.bashrc; then
  echo 'export KUBECONFIG=~/.kube/config' >> ~/.bashrc
fi
export KUBECONFIG=~/.kube/config

echo ============================================================
echo Installing Helm v4
echo ============================================================
HELM_VERSION=$(curl -sSL https://api.github.com/repos/helm/helm/releases \
  | grep '"tag_name"' \
  | grep '"v4\.' \
  | head -1 \
  | cut -d'"' -f4)
echo "Installing Helm ${HELM_VERSION}..."
curl -sSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" \
  | sudo tar xz --strip-components=1 -C /usr/local/bin linux-amd64/helm
helm version

echo ""
sudo microk8s kubectl get nodes
echo ""
sudo microk8s kubectl get pods -A
echo ""

VM_IP=$(hostname -I | awk '{print $1}')
echo ==========================================================================
echo MicroK8s installation complete!
echo ""
echo "  Ubuntu version : ${UBUNTU_VERSION}"
echo "  Helm version   : $(helm version --short)"
echo ""
echo Next step:
echo "  1. Reboot the VM:  sudo reboot"
echo "  2. After reboot:   ./2-install-camunda-microk8s.sh"
echo ==========================================================================
