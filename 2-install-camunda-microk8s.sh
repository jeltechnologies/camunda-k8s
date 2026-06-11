#!/usr/bin/env bash
set -euo pipefail

echo ==================================================================
echo Environment
echo ==================================================================
./configure-env.sh
source ./install-env.sh

echo "=================================================================="
echo "Checking Helm version"
echo "=================================================================="
HELM_MAJOR=$(helm version --short 2>/dev/null | grep -oP 'v\K[0-9]+' | head -1)
if [[ "${HELM_MAJOR}" -lt 4 ]]; then
  echo "ERROR: Helm v4 is required (detected v${HELM_MAJOR}). Run 1-install-microk8s.sh to upgrade."
  exit 1
fi
echo "Helm $(helm version --short) — OK"

echo ==========================================
echo Fixing hosts file
echo ==========================================
sudo ./install-fix-hosts.sh

echo "=================================================================="
echo Adding Helm repositories
echo "=================================================================="
helm repo add camunda https://helm.camunda.io
helm repo update

echo "Camunda domain          : ${CAMUNDA_DOMAIN}"
echo "Password                : ${PASSWORD}"
echo "------------------------------------------------------------------"
echo "Workflow engine domain  : ${ZEEBE_DOMAIN}"
echo "Kubernetes namespace    : camunda"
echo "Helm chart version      : ${HELM_CHART_VERSION}"
echo "Elasticsearch version   : ${ES_VERSION}"
echo "PostgreSQL version      : ${PG_VERSION}"
echo "Keycloak version        : ${KEYCLOAK_VERSION}"

echo "=================================================================="
echo Configuring Zeebe gRPC TCP passthrough for nginx ingress
echo "=================================================================="
microk8s kubectl get namespace ingress 2>/dev/null || microk8s kubectl create namespace ingress
microk8s kubectl create configmap nginx-ingress-tcp-microk8s-conf \
  --namespace ingress \
  --from-literal=26500="camunda/camunda-zeebe-gateway:26500" \
  --dry-run=client -o yaml | microk8s kubectl apply -f -

echo "=================================================================="
echo Waiting for nginx ingress to be ready
echo "=================================================================="
microk8s kubectl wait --namespace ingress \
  --for=condition=ready pod \
  --selector=name=nginx-ingress-microk8s \
  --timeout=120s

echo "=================================================================="
echo Creating namespace, TLS certificates and passwords
echo "=================================================================="
if ! microk8s kubectl get namespace camunda &>/dev/null; then
    microk8s kubectl create namespace camunda
fi

./create-certifcate.sh "${CAMUNDA_DOMAIN}" -n camunda
./create-certifcate.sh "${ZEEBE_DOMAIN}"   -n camunda

echo "=================================================================="
echo Setting passwords for the cluster
echo "=================================================================="
microk8s kubectl delete secret camunda-credentials -n camunda --ignore-not-found
microk8s kubectl create secret generic camunda-credentials \
    --from-literal=identity-keycloak-admin-password="${PASSWORD}" \
    --from-literal=identity-firstuser-password="${PASSWORD}" \
    --from-literal=identity-connectors-client-token="${PASSWORD}" \
    --from-literal=identity-optimize-client-token="${PASSWORD}" \
    --from-literal=identity-orchestration-client-token="${PASSWORD}" \
    --from-literal=webmodeler-postgresql-user-password="${PASSWORD}" \
    --from-literal=orchestration-postgresql-password="${PASSWORD}" \
    -n camunda


echo "******************************************************************"
echo "Installation is starting"
echo ""
echo "P L E A S E  W A I T - this may take up to 20 minutes"
echo 
echo "******************************************************************"

echo "=================================================================="
echo "Installing Elasticsearch ${ES_VERSION}"
echo "=================================================================="

envsubst '${ES_VERSION}' \
  < template-elasticsearch.yaml | microk8s kubectl apply -f -

echo "Waiting for Elasticsearch to be ready..."
microk8s kubectl rollout status statefulset/camunda-elasticsearch-master -n camunda --timeout=5m
echo "Elasticsearch installed. Service: camunda-elasticsearch-master:9200"

echo "=================================================================="
echo "Installing PostgreSQL ${PG_VERSION}"
echo "=================================================================="
envsubst '${PASSWORD} ${PG_VERSION}' \
  < template-postgresql.yaml | microk8s kubectl apply -f -

echo "Waiting for PostgreSQL to be ready..."
microk8s kubectl rollout status statefulset/camunda-postgresql -n camunda --timeout=3m
echo "PostgreSQL installed. Service: camunda-postgresql:5432"

echo "=================================================================="
echo "Installing Keycloak ${KEYCLOAK_VERSION}"
echo "=================================================================="
envsubst '${KEYCLOAK_VERSION} ${CAMUNDA_DOMAIN} ${PASSWORD}' \
  < template-keycloak.yaml | microk8s kubectl apply -f -

echo "Waiting for Keycloak to be ready..."
microk8s kubectl rollout status statefulset/camunda-keycloak -n camunda --timeout=5m
echo "Keycloak installed. Service: camunda-keycloak:80/auth"

envsubst '${CAMUNDA_DOMAIN}' \
  < template-keycloak-ingress.yaml | microk8s kubectl apply -f -

echo "=================================================================="
echo Uninstalling previous Camunda installation if present
echo "=================================================================="
helm uninstall camunda -n camunda 2>/dev/null || true

microk8s kubectl delete pvc camunda-connectors-custom -n camunda --ignore-not-found
microk8s kubectl delete pv  camunda-connectors-pv           --ignore-not-found

echo "=================================================================="
echo Generating Helm values from template
echo "=================================================================="
envsubst '${CAMUNDA_DOMAIN} ${ZEEBE_DOMAIN} ${CAMUNDA_APP_VERSION} ${OLLAMA_ENABLED} ${OLLAMA_MODEL} ${OLLAMA_URL} ${GITLAB_URL}' \
  < template-values-camunda.yaml > values-camunda.yaml

echo "=================================================================="
echo Creating host directories for Camunda volumes
echo "=================================================================="
mkdir -p ~/camunda-docs
mkdir -p ~/camunda-connectors

echo "=================================================================="
echo Creating PVs and PVCs for document storage and custom connectors
echo "=================================================================="
envsubst '${HOME}' \
  < template-volumes.yaml | microk8s kubectl apply -f -
echo "PVC camunda-docs-pvc created"
echo "PVC camunda-connectors-custom created"

echo ""
echo "=================================================================="
echo "Installing Camunda..."
echo "=================================================================="
helm install camunda camunda/camunda-platform \
  --version "${HELM_CHART_VERSION}" \
  --namespace camunda \
  -f values-camunda.yaml \
  --timeout 20m \
  --wait

echo "=================================================================="
echo Updating connector secrets and patching deployment
echo "=================================================================="
./update-connector-secrets.sh

echo ""
echo "=================================================================="
echo "Camunda started successfully!"
echo ""
microk8s kubectl get pods -n camunda
echo "=================================================================="
echo ""
echo "============================================================"
echo "  Camunda installation complete!"
echo "============================================================"
echo ""
echo "  URL:      https://${CAMUNDA_DOMAIN}"
echo "  Keycloak: https://${CAMUNDA_DOMAIN}/auth"
echo "  Identity: https://${CAMUNDA_DOMAIN}/identity"
echo "  Modeler:  https://${CAMUNDA_DOMAIN}/modeler"
echo "  Optimize: https://${CAMUNDA_DOMAIN}/optimize"
echo "  Zeebe:    grpc://${ZEEBE_DOMAIN}:26500"
echo ""
echo "  Watch pod status with:"
echo "  microk8s kubectl get pods -n camunda -w"
echo ""
echo "  Username: demo"
echo "  Password: ${PASSWORD}"
echo ""
echo "  Document storage : ~/camunda-docs"
echo "  Custom connectors: ~/camunda-connectors"
