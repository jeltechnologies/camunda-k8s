#!/usr/bin/env bash

set -euo pipefail

KUBECTL="microk8s kubectl"

NAMESPACE="default"
DAYS="365"

usage() {
    cat <<EOF
Usage: $0 [options] <domain> [days] [output_prefix]

Options:
  -n <namespace>    Namespace for the TLS secret (default: default)

Positional arguments:
  <domain>          Domain name for the certificate (e.g. camunda.home.jeltechnologies.com)
  [days]            Optional.  Validity period in days (default: 365)
  [output_prefix]   Optional.  Prefix for the output files (default: <domain>)

Examples:
  $0 camunda.home.jeltechnologies.com
  $0 camunda.home.jeltechnologies.com -n camunda
  $0 camunda.home.jeltechnologies.com -n camunda 180 myprefix
EOF
    exit 1
}

if [[ $# -lt 1 ]]; then
    echo "Error: domain name is required." >&2
    usage
fi

DOMAIN="${1}"
shift   # remove domain from the list that getopts will see

while getopts ":n:" opt; do
    case $opt in
        n) NAMESPACE=${OPTARG} ;;
        \? ) echo "Invalid option: -${OPTARG}" >&2; usage ;;
        : )  echo "Option -${OPTARG} requires an argument." >&2; usage ;;
    esac
done
shift $((OPTIND-1))   # remove options that were processed

if [[ $# -gt 0 && "${1:0:1}" != "-" ]]; then
    DAYS="${1}"
    shift
fi
PREFIX="${1:-$DOMAIN}"

CERT_DIR="."
KEY_FILE="${CERT_DIR}/${PREFIX}.key.pem"
CRT_FILE="${CERT_DIR}/${PREFIX}.cert.pem"
SECRET_NAME="tls-secret-${DOMAIN}"

echo "Generating RSA key and self‑signed cert..."
echo "  Domain     : ${DOMAIN}"
echo "  Validity   : ${DAYS} days"
echo "  Namespace  : ${NAMESPACE}"
echo "  Output dir : $CERT_DIR"

openssl req \
    -x509 \
    -newkey rsa:4096 \
    -keyout "$KEY_FILE" \
    -out "$CRT_FILE" \
    -sha256 \
    -days "$DAYS" \
    -nodes \
    -subj "/CN=${DOMAIN}" \
    -addext "subjectAltName=DNS:${DOMAIN}"

echo
echo "Installing secret in namespace \`${NAMESPACE}\`..."

${KUBECTL} delete secret "$SECRET_NAME" -n "$NAMESPACE" --ignore-not-found
${KUBECTL} create secret tls "$SECRET_NAME" \
    --cert="$CRT_FILE" \
    --key="$KEY_FILE" \
    -n "$NAMESPACE"

echo
echo "✅  Done."
echo "  • Key file   : $KEY_FILE"
echo "  • Cert file  : $CRT_FILE"
echo "  • K8s secret : ${SECRET_NAME} (namespace: ${NAMESPACE})"

rm "$CRT_FILE"
rm "$KEY_FILE"
