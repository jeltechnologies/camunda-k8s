#!/usr/bin/env bash
# Shared helpers for the cluster-wide password. It is never written to disk: after the first
# install the camunda-credentials Secret in the cluster is the only place that holds it.
# Source this file; it defines functions only.

# Prints the password stored in the cluster, or nothing if there is none yet.
read_cluster_password() {
  microk8s kubectl get secret camunda-credentials -n camunda \
    -o jsonpath='{.data.keycunda-postgresql-password}' 2>/dev/null | base64 -d 2>/dev/null || true
}

# Exports PASSWORD: already set in the environment, else the cluster's (if the user agrees),
# else a new one typed twice with hidden input.
obtain_password() {
  if [[ -n "${PASSWORD:-}" ]]; then
    export PASSWORD
    return
  fi

  local existing keep p1 p2
  existing="$(read_cluster_password)"
  if [[ -n "${existing}" ]]; then
    read -r -p "Keep the existing password? [Y/n]: " keep
    if [[ ! "${keep}" =~ ^[Nn] ]]; then
      PASSWORD="${existing}"
      export PASSWORD
      return
    fi
  fi

  while true; do
    read -r -s -p "Enter password (input hidden): " p1; echo
    read -r -s -p "Repeat password: " p2; echo
    [[ -n "${p1}" && "${p1}" == "${p2}" ]] && break
    echo "Passwords were empty or did not match, try again."
  done
  PASSWORD="${p1}"
  export PASSWORD
}
