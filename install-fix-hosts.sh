#!/usr/bin/env bash

source ./install-env.sh

# When behind a reverse proxy (Cloudflare, Pangolin, etc.) the public domain
# resolves externally, so we must NOT add it to /etc/hosts or traffic will
# loop back instead of going through the proxy.
if [[ "${BEHIND_REVERSE_PROXY:-false}" == "true" ]]; then
  ENTRIES=(
      "127.0.0.1 camunda.local"
      "127.0.0.1 zeebe.camunda.local"
  )
  echo "Behind reverse proxy — skipping /etc/hosts entries for ${CAMUNDA_DOMAIN} and ${ZEEBE_DOMAIN}."
else
  ENTRIES=(
      "127.0.0.1 camunda.local"
      "127.0.0.1 zeebe.camunda.local"
      "127.0.0.1 ${CAMUNDA_DOMAIN}"
      "127.0.0.1 ${ZEEBE_DOMAIN}"
  )
fi

HOSTS_FILE="/etc/hosts"

if [[ $EUID -ne 0 ]]; then
    echo "This script must be run as root (use sudo)"
    exit 1
fi

entries_to_add=()
for entry in "${ENTRIES[@]}"; do
    if ! grep -qF "${entry}" "${HOSTS_FILE}"; then
        entries_to_add+=("${entry}")
    else
        echo "Already exists: ${entry}"
    fi
done

if [[ ${#entries_to_add[@]} -gt 0 ]]; then
    echo "Adding entries to ${HOSTS_FILE}..."
    
    for entry in "${entries_to_add[@]}"; do
        echo "${entry}" >> "${HOSTS_FILE}"
    done
    
    echo "Added ${#entries_to_add[@]} entry/entries:"
    for entry in "${entries_to_add[@]}"; do
        echo "  ${entry}"
    done
else
    echo "All entries already exist in ${HOSTS_FILE}"
fi
