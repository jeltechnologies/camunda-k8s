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
echo "Installing Docker Engine"
echo ============================================================
# Convenience for demos that run extra containers alongside the cluster
# (local registry, test tooling, ...). get.docker.com detects the distro
# and uses sudo internally. Group membership only takes effect on next
# login - the reboot this script tells you to do at the end covers it, so
# no `newgrp docker` here (it would replace this shell and abort the rest
# of the script).
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"

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

echo ============================================================
echo "Installing Camunda developer tooling (c8ctl + AI skills)"
echo ============================================================
# Done last, after Kubernetes and Helm: none of this is needed to bring up
# the cluster, so a hiccup here can't block the platform install. The example
# c8ctl profile (which needs ${CAMUNDA_DOMAIN}) is still created later, by
# 2-install-camunda-microk8s.sh.

# Node.js (latest LTS) - only needed here, as the runtime for the c8ctl CLI.
# Ubuntu's own packages ship a Node major that's too old (e.g. 18.x on 24.04);
# NodeSource's "lts" alias always resolves to the current LTS with no version
# number to bump by hand later.
curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
sudo apt install -y nodejs
echo "Node.js $(node -v), npm $(npm -v)"

# c8ctl - the Camunda 8 CLI - installed globally so every user and shell has it.
sudo npm install -g @camunda8/cli

# Camunda AI skills (github.com/camunda/skills): cloned once to a shared location
# and symlinked into ~/.claude/skills, which Claude Code loads for EVERY project
# and working directory (not just this repo). /etc/skel gets the same links so
# users created later inherit them. Update everyone at once with:
#   git -C /opt/camunda-skills pull --ff-only
CAMUNDA_SKILLS_DIR=/opt/camunda-skills
if [[ -d "${CAMUNDA_SKILLS_DIR}/.git" ]]; then
  git -C "${CAMUNDA_SKILLS_DIR}" pull --ff-only
else
  # Only creating the dir under /opt needs root; the clone itself runs as the
  # user so the working tree and .git stay user-owned (no "dubious ownership"
  # or root-owned objects on a later `git pull`).
  sudo mkdir -p "${CAMUNDA_SKILLS_DIR}"
  sudo chown "$USER":"$USER" "${CAMUNDA_SKILLS_DIR}"
  git clone https://github.com/camunda/skills.git "${CAMUNDA_SKILLS_DIR}"
fi

mkdir -p "${HOME}/.claude/skills"
for skill in "${CAMUNDA_SKILLS_DIR}"/skills/*/; do
  ln -sfn "${skill%/}" "${HOME}/.claude/skills/$(basename "${skill}")"
done

sudo mkdir -p /etc/skel/.claude/skills
for skill in "${CAMUNDA_SKILLS_DIR}"/skills/*/; do
  sudo ln -sfn "${skill%/}" "/etc/skel/.claude/skills/$(basename "${skill}")"
done
echo "Camunda AI skills linked into ~/.claude/skills - usable from any directory"

VM_IP=$(hostname -I | awk '{print $1}')
echo ==========================================================================
echo MicroK8s installation complete!
echo ""
echo "  Ubuntu version : ${UBUNTU_VERSION}"
echo "  Helm version   : $(helm version --short)"
echo "  c8ctl          : $(npm ls -g --depth 0 @camunda8/cli 2>/dev/null | grep -o '@camunda8/cli@[0-9.]*' || echo 'installed')"
echo "  Camunda skills : ~/.claude/skills -> ${CAMUNDA_SKILLS_DIR}/skills/*"
echo ""
echo Next step:
echo "  1. Reboot the VM:  sudo reboot"
echo "  2. After reboot:   ./2-install-camunda-microk8s.sh"
echo ==========================================================================
