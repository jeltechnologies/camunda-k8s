#!/usr/bin/env bash

DEFAULT_CAMUNDA_DOMAIN=$(hostname).example.com
DEFAULT_PASSWORD=Choose_a_secure_password_please
DEFAULT_HELM_CHART_VERSION=15.0.0-alpha2
DEFAULT_CAMUNDA_APP_VERSION=8.10.0

ES_VERSION=8.19.9
KEYCLOAK_VERSION=26.2.5
PG_VERSION=16

DEFAULT_OLLAMA_ENABLED=false
DEFAULT_OLLAMA_MODEL=my-model
DEFAULT_OLLAMA_URL=http://my-ollama:11434

DEFAULT_GITLAB_ENABLED=false
DEFAULT_GITLAB_URL=https://my-gitlab/api/v4

DEFAULT_BEHIND_REVERSE_PROXY=false
DEFAULT_SWAGGER_ENABLED=false

if [[ -f ./install-env.sh ]]; then
  echo ""
  echo "  Found existing install-env.sh — using previous values as defaults."
  source ./install-env.sh

  DEFAULT_CAMUNDA_DOMAIN="${CAMUNDA_DOMAIN}"
  DEFAULT_PASSWORD="${PASSWORD}"
  DEFAULT_HELM_CHART_VERSION="${HELM_CHART_VERSION}"
  DEFAULT_CAMUNDA_APP_VERSION="${CAMUNDA_APP_VERSION}"
  DEFAULT_OLLAMA_ENABLED="${OLLAMA_ENABLED}"
  DEFAULT_OLLAMA_MODEL="${OLLAMA_MODEL}"
  DEFAULT_OLLAMA_URL="${OLLAMA_URL}"

  if [[ -n "${GITLAB_URL:-}" ]]; then
    DEFAULT_GITLAB_ENABLED=true
    DEFAULT_GITLAB_URL="${GITLAB_URL}"
  else
    DEFAULT_GITLAB_ENABLED=false
  fi

  DEFAULT_BEHIND_REVERSE_PROXY="${BEHIND_REVERSE_PROXY:-false}"
  DEFAULT_SWAGGER_ENABLED="${SWAGGER_ENABLED:-false}"
fi

echo "============================================================"
echo " Camunda configuration"
echo "============================================================"
echo ""

read -p "Enter Camunda domain (default: ${DEFAULT_CAMUNDA_DOMAIN}): " input_domain
CAMUNDA_DOMAIN=${input_domain:-$DEFAULT_CAMUNDA_DOMAIN}

read -p "Enter password (default: ${DEFAULT_PASSWORD}): " input_password
PASSWORD=${input_password:-$DEFAULT_PASSWORD}

read -p "Enter Helm chart version. See https://helm.camunda.io/camunda-platform/version-matrix/ (default: ${DEFAULT_HELM_CHART_VERSION}): " input_helm_version
HELM_CHART_VERSION=${input_helm_version:-$DEFAULT_HELM_CHART_VERSION}

read -p "Enter Camunda application version (default: ${DEFAULT_CAMUNDA_APP_VERSION}): " input_app_version
CAMUNDA_APP_VERSION=${input_app_version:-$DEFAULT_CAMUNDA_APP_VERSION}

ZEEBE_DOMAIN="zeebe.${CAMUNDA_DOMAIN}"

read -p "Exposed to internet, behind a reverse proxy? Choose false when you are not sure. (default: ${DEFAULT_BEHIND_REVERSE_PROXY}): " input_reverse_proxy
BEHIND_REVERSE_PROXY=${input_reverse_proxy:-$DEFAULT_BEHIND_REVERSE_PROXY}


echo ""
echo "============================================================"
echo " Optional: Ollama AI Copilot"
echo " Enables BPMN/FEEL/Form AI assistance in the Web Modeler"
echo "============================================================"
echo ""

read -p "Enable Ollama AI copilot? (default: ${DEFAULT_OLLAMA_ENABLED}): " input_ollama_enabled
OLLAMA_ENABLED=${input_ollama_enabled:-$DEFAULT_OLLAMA_ENABLED}

if [[ "$OLLAMA_ENABLED" == "true" ]]; then
  read -p "Enter Ollama model name (default: ${DEFAULT_OLLAMA_MODEL}): " input_ollama_model
  OLLAMA_MODEL=${input_ollama_model:-$DEFAULT_OLLAMA_MODEL}

  read -p "Enter Ollama base URL (default: ${DEFAULT_OLLAMA_URL}): " input_ollama_url
  OLLAMA_URL=${input_ollama_url:-$DEFAULT_OLLAMA_URL}
else
  OLLAMA_MODEL=${DEFAULT_OLLAMA_MODEL}
  OLLAMA_URL=${DEFAULT_OLLAMA_URL}
fi

echo ""
echo "============================================================"
echo " Optional: GitLab Git Sync"
echo " Enables Git sync in the Web Modeler"
echo "============================================================"
echo ""

read -p "Enable GitLab Git Sync? (default: ${DEFAULT_GITLAB_ENABLED}): " input_gitlab_enabled
GITLAB_ENABLED=${input_gitlab_enabled:-$DEFAULT_GITLAB_ENABLED}

if [[ "$GITLAB_ENABLED" == "true" ]]; then
  read -p "Enter GitLab base URL (default: ${DEFAULT_GITLAB_URL}): " input_gitlab_url
  GITLAB_URL=${input_gitlab_url:-$DEFAULT_GITLAB_URL}
else
  GITLAB_URL=""
fi

echo ""
echo "=========================================================================================================="
echo " Optional: Enable Swagger"
echo " Exposes the full REST API documentation publicly, which is a security risk when exposed to the internet"
echo " Keep false (default) unless really needed."
echo "=========================================================================================================="
echo ""

read -p "Enable Swagger UI? WARNING: do not enable on public internet. (default: ${DEFAULT_SWAGGER_ENABLED}): " input_swagger_enabled
SWAGGER_ENABLED=${input_swagger_enabled:-$DEFAULT_SWAGGER_ENABLED}

cat > install-env.sh <<ENVEOF
#!/usr/bin/env bash
export CAMUNDA_DOMAIN="${CAMUNDA_DOMAIN}"
export PASSWORD="${PASSWORD}"
export ZEEBE_DOMAIN="${ZEEBE_DOMAIN}"
export HELM_CHART_VERSION="${HELM_CHART_VERSION}"
export CAMUNDA_APP_VERSION="${CAMUNDA_APP_VERSION}"
export ES_VERSION="${ES_VERSION}"
export KEYCLOAK_VERSION="${KEYCLOAK_VERSION}"
export PG_VERSION="${PG_VERSION}"
export OLLAMA_ENABLED="${OLLAMA_ENABLED}"
export OLLAMA_MODEL="${OLLAMA_MODEL}"
export OLLAMA_URL="${OLLAMA_URL}"
export GITLAB_URL="${GITLAB_URL}"
export BEHIND_REVERSE_PROXY="${BEHIND_REVERSE_PROXY}"
export SWAGGER_ENABLED="${SWAGGER_ENABLED}"
ENVEOF

echo ""
echo "install-env.sh has been created with the following content:"
cat install-env.sh
