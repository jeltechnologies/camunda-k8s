#!/usr/bin/env bash

source ./install-env.sh

ENTRIES=(
    "127.0.0.1 camunda.local"
    "127.0.0.1 zeebe.camunda.local"
	"127.0.0.1 ${CAMUNDA_DOMAIN}"
	"127.0.0.1 ${ZEEBE_DOMAIN}"
)

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
