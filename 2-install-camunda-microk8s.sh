#!/usr/bin/env bash
set -euo pipefail

# 1-install-microk8s.sh adds $USER to the 'microk8s' group, but that only
# takes effect in a new login session. If this shell predates that (no
# reboot/re-login since install), re-exec ourselves under the group via
# sg instead of failing with "Insufficient permissions to access MicroK8s".
if getent group microk8s | grep -qw "$USER" && ! id -nG | grep -qw microk8s; then
  echo "=================================================================="
  echo "Applying 'microk8s' group membership for this session"
  echo "=================================================================="
  exec sg microk8s -c "$(printf '%q ' "$0" "$@")"
fi

echo ==================================================================
echo Welcome to the Camunda microk8s installer
echo ==================================================================
./configure-env.sh
source ./install-env.sh

if [[ "${INSTALL_C8CTL_SKILLS:-true}" == "true" ]]; then
  echo "=================================================================="
  echo "Installing c8ctl and Camunda AI skills"
  echo "=================================================================="
  # Node.js/npm (latest LTS, required by both packages below) was already
  # installed by 1-install-microk8s.sh. Done up front, right after the
  # question wizard, since neither package depends on anything the rest of
  # this script sets up - only ${CAMUNDA_DOMAIN}, already available from
  # install-env.sh above.
  sudo npm install -g @camunda8/cli
  npx --yes skills add camunda/skills --skill '*'

  echo "Creating example c8ctl profile 'dev' - update the client secret below"
  echo "after creating a matching 'c8ctl' client (audience: orchestration-api)"
  echo "at https://${CAMUNDA_DOMAIN}/keycunda/clients"
  c8 add profile dev \
    --baseUrl="https://${CAMUNDA_DOMAIN}/orchestration" \
    --clientId=c8ctl \
    --clientSecret=UPDATE_ME \
    --audience=orchestration-api \
    --oAuthUrl="https://${CAMUNDA_DOMAIN}/auth/oauth2/token"
fi

sudo echo "Please provide your sudo password"

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
echo "Keycunda image          : ${KEYCUNDA_IMAGE}"

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
echo Creating namespaces, TLS certificates and passwords
echo "=================================================================="
if ! microk8s kubectl get namespace camunda &>/dev/null; then
    microk8s kubectl create namespace camunda
fi

./create-certifcate.sh "${CAMUNDA_DOMAIN}" -n camunda
./create-certifcate.sh "${ZEEBE_DOMAIN}"   -n camunda

# When behind a reverse proxy, pods making their own outbound HTTPS calls to $CAMUNDA_DOMAIN
# (OIDC discovery) would otherwise leave the cluster and loop back through the external proxy,
# hitting its cert instead of the one global.tls.caBundle trusts. Override CoreDNS so
# $CAMUNDA_DOMAIN resolves in-cluster to this node's own IP, landing on this box's nginx-ingress
# instead - see "Ingress-behind-proxy pitfalls" / the caBundle comments in the template-values-*
# files for the full investigation.
if [[ "${BEHIND_REVERSE_PROXY:-false}" == "true" ]]; then
  echo "=================================================================="
  echo "Behind reverse proxy - pointing \$CAMUNDA_DOMAIN at this node in CoreDNS"
  echo "=================================================================="
  NODE_INTERNAL_IP=$(microk8s kubectl get node -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
  echo "Node internal IP: ${NODE_INTERNAL_IP}"
  microk8s kubectl create configmap coredns -n kube-system \
    --from-literal=Corefile="$(cat <<COREFILE
.:53 {
    errors
    health {
      lameduck 5s
    }
    ready
    hosts {
      ${NODE_INTERNAL_IP} ${CAMUNDA_DOMAIN}
      fallthrough
    }
    log . {
      class error
    }
    kubernetes cluster.local in-addr.arpa ip6.arpa {
      pods insecure
      fallthrough in-addr.arpa ip6.arpa
    }
    prometheus :9153
    forward . /etc/resolv.conf
    cache 30
    loop
    reload
    loadbalance
}
COREFILE
)" --dry-run=client -o yaml | microk8s kubectl apply -f -
  microk8s kubectl rollout restart deployment coredns -n kube-system
  microk8s kubectl rollout status deployment coredns -n kube-system --timeout=60s
fi

echo "=================================================================="
echo Setting passwords for the cluster
echo "=================================================================="
microk8s kubectl delete secret camunda-credentials -n camunda --ignore-not-found
microk8s kubectl create secret generic camunda-credentials \
    --from-literal=identity-identity-client-token="${PASSWORD}" \
    --from-literal=identity-connectors-client-token="${PASSWORD}" \
    --from-literal=identity-optimize-client-token="${PASSWORD}" \
    --from-literal=identity-orchestration-client-token="${PASSWORD}" \
    --from-literal=webmodeler-postgresql-user-password="${PASSWORD}" \
    --from-literal=orchestration-postgresql-password="${PASSWORD}" \
    --from-literal=identity-postgresql-password="${PASSWORD}" \
    --from-literal=keycunda-postgresql-password="${PASSWORD}" \
    -n camunda

echo "=================================================================="
echo Generating the Keycunda signing key, if it does not already exist
echo "=================================================================="
if microk8s kubectl get secret keycunda-signing-key -n camunda &>/dev/null; then
  echo "keycunda-signing-key already exists - keeping it, so existing tokens stay valid."
else
  echo "No existing signing key found, generating one..."
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    | microk8s kubectl create secret generic keycunda-signing-key \
        --from-file=private-key.pem=/dev/stdin \
        -n camunda
fi


echo "******************************************************************"
echo "Installation is starting"
echo ""
echo "P-L-E-A-S-E  W-A-I-T"
echo ""
echo "This may take up to 20 minutes"
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
echo "Ensuring PostgreSQL app users/databases exist with the current password"
echo "=================================================================="
# camunda-postgresql-init only runs against a fresh PGDATA volume, so a
# database added after this box's PVC was first initialized (or a $PASSWORD
# that changed across runs) never gets picked up on re-install. Self-heal it
# here: create anything missing (ignore "already exists"), then always
# re-sync each user's password to the current $PASSWORD.
for pair in "keycunda:keycunda" "webmodeler:web-modeler" "orchestration:orchestration" "identity:identity"; do
  db_user="${pair%%:*}"
  db_name="${pair##*:}"
  if microk8s kubectl exec -n camunda camunda-postgresql-0 -- psql -U postgres -c \
    "CREATE USER \"${db_user}\" WITH PASSWORD '${PASSWORD}';" >/dev/null 2>&1; then
    echo "Role ${db_user} created"
  else
    echo "Role ${db_user} exists"
  fi
  if microk8s kubectl exec -n camunda camunda-postgresql-0 -- psql -U postgres -c \
    "CREATE DATABASE \"${db_name}\" OWNER \"${db_user}\";" >/dev/null 2>&1; then
    echo "Database ${db_name} created"
  else
    echo "Database ${db_name} exists"
  fi
  microk8s kubectl exec -n camunda camunda-postgresql-0 -- psql -U postgres -c \
    "GRANT ALL PRIVILEGES ON DATABASE \"${db_name}\" TO \"${db_user}\";"
  microk8s kubectl exec -n camunda camunda-postgresql-0 -- psql -U postgres -c \
    "ALTER USER \"${db_user}\" WITH PASSWORD '${PASSWORD}';"
done

echo "=================================================================="
echo Setting up RBAC for Keycunda Secrets Management functions
echo "=================================================================="
microk8s kubectl apply -f template-keycunda-rbac.yaml

echo "=================================================================="
echo "Installing Keycunda (${KEYCUNDA_IMAGE})"
echo "=================================================================="
envsubst '${KEYCUNDA_IMAGE} ${CAMUNDA_DOMAIN} ${PASSWORD} ${DEMO_NAME} ${DEMO_EMAIL}' \
  < template-keycunda.yaml | microk8s kubectl apply -f -

echo "Waiting for Keycunda to be ready..."
microk8s kubectl rollout status deployment/keycunda -n camunda --timeout=5m
echo "Keycunda installed. Service: keycunda.camunda:80/auth"

echo "=================================================================="
echo "Installing Keycunda ingress"
echo "=================================================================="
# Delete-then-apply instead of a plain apply, so stale/conflicting rules from
# a previous run never linger - the ingress is always recreated fresh.
rendered_keycunda_ingress=$(envsubst '${CAMUNDA_DOMAIN}' < template-keycunda-ingress.yaml)
echo "${rendered_keycunda_ingress}" | microk8s kubectl delete -f - --ignore-not-found
echo "${rendered_keycunda_ingress}" | microk8s kubectl apply -f -

echo "=================================================================="
echo Uninstalling previous Camunda installation if present
echo "=================================================================="
# Only removes resources the Helm release itself owns - the "connector-secrets" Secret that
# Keycunda's Secrets Management page manages (secret/ClusterSecretsApplier.java) is created
# imperatively, outside this release, specifically so admin-added/removed connector secrets
# survive every reinstall. Never add a step here that deletes it directly.
helm uninstall camunda -n camunda 2>/dev/null || true

microk8s kubectl delete pvc camunda-connectors-custom -n camunda --ignore-not-found
microk8s kubectl delete pv  camunda-connectors-pv           --ignore-not-found

echo "=================================================================="
echo Generating Helm values from template
echo "=================================================================="
# template-values-camunda.yaml targets the 8.9 chart line (14.x). The 8.10 chart line (15.x+)
# needs its own template - see the header comment in template-values-camunda-8.10.yaml for why a
# full copy was used instead of an overlay/diff. Selected on HELM_CHART_VERSION's major version,
# not CAMUNDA_APP_VERSION, since the Helm chart is what these templates' values.yaml schema
# actually has to match.
camunda_values_template="template-values-camunda.yaml"
if [[ "${HELM_CHART_VERSION%%.*}" -ge 15 ]]; then
  camunda_values_template="template-values-camunda-8.10.yaml"
fi
echo "Using Helm values template: ${camunda_values_template}"
envsubst '${CAMUNDA_DOMAIN} ${ZEEBE_DOMAIN} ${CAMUNDA_APP_VERSION} ${OLLAMA_ENABLED} ${OLLAMA_MODEL} ${OLLAMA_URL} ${GITLAB_URL} ${SWAGGER_ENABLED} ${DEMO_EMAIL} ${DEMO_NAME} ${PASSWORD}' \
  < "$camunda_values_template" > values-camunda.yaml

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
echo Seeding Identity mapping rule for baseline demo user access
echo "=================================================================="
./seed-identity-mapping-rules.sh

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
echo "  Auth:     https://${CAMUNDA_DOMAIN}/auth"
echo "  Identity: https://${CAMUNDA_DOMAIN}/identity"
echo "  Modeler:  https://${CAMUNDA_DOMAIN}/modeler"
echo "  Optimize: https://${CAMUNDA_DOMAIN}/optimize"
echo "  Swagger:  https://${CAMUNDA_DOMAIN}/orchestration/swagger"
echo "  Zeebe:    grpc://${ZEEBE_DOMAIN}:26500"
echo ""
echo "  Watch pod status with:"
echo "  microk8s kubectl get pods -n camunda -w"
echo ""
echo "  Name:              ${DEMO_NAME}"
echo "  Log in with email: ${DEMO_EMAIL}"
echo "  Password:          ${PASSWORD}"
echo ""
echo "  Document storage : ~/camunda-docs"
echo "  Custom connectors: ~/camunda-connectors"
echo ""
if [[ "${INSTALL_C8CTL_SKILLS:-true}" == "true" ]]; then
  echo "  c8ctl profile 'dev' created with a placeholder secret - create a matching"
  echo "  'c8ctl' client (audience: orchestration-api) at"
  echo "  https://${CAMUNDA_DOMAIN}/keycunda/clients, then update it with:"
  echo "  c8 add profile dev --clientSecret=<real-secret> --baseUrl=https://${CAMUNDA_DOMAIN}/orchestration --clientId=c8ctl --audience=orchestration-api --oAuthUrl=https://${CAMUNDA_DOMAIN}/auth/oauth2/token"
  echo ""
fi
