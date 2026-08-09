#!/usr/bin/env bash

DEFAULT_CAMUNDA_DOMAIN=$(hostname).example.com
DEFAULT_PASSWORD=Choose_a_secure_password_please
DEFAULT_HELM_CHART_VERSION=14.7.0
DEFAULT_CAMUNDA_APP_VERSION=8.9.12
DEFAULT_DEMO_NAME="Demo User"
DEFAULT_DEMO_EMAIL=demo@example.com

DEFAULT_OLLAMA_ENABLED=false
DEFAULT_OLLAMA_MODEL=my-model
DEFAULT_OLLAMA_URL=http://my-ollama:11434

DEFAULT_GITLAB_ENABLED=false
DEFAULT_GITLAB_URL=https://my-gitlab/api/v4

DEFAULT_BEHIND_REVERSE_PROXY=false
DEFAULT_SWAGGER_ENABLED=false

if [[ -f ./install-env.sh ]]; then
  source ./install-env.sh

  DEFAULT_CAMUNDA_DOMAIN="${CAMUNDA_DOMAIN}"
  DEFAULT_PASSWORD="${PASSWORD}"
  DEFAULT_HELM_CHART_VERSION="${HELM_CHART_VERSION}"
  DEFAULT_CAMUNDA_APP_VERSION="${CAMUNDA_APP_VERSION}"
  DEFAULT_DEMO_NAME="${DEMO_NAME:-$DEFAULT_DEMO_NAME}"
  DEFAULT_DEMO_EMAIL="${DEMO_EMAIL:-$DEFAULT_DEMO_EMAIL}"
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

# Hardcoded constants, not remembered from a previous install-env.sh - set after the reuse
# block above so an old install-env.sh (which may predate one of these, or hold a stale value)
# can never clobber them.
ES_VERSION=8.19.9
PG_VERSION=16
KEYCUNDA_IMAGE=ghcr.io/jeltechnologies/keycunda:latest

VERSION_MATRIX_URL="https://helm.camunda.io/camunda-platform/version-matrix/"
MTX_ALPHA_CHART=""; MTX_ALPHA_CAMUNDA=""
MTX_STABLE_CHART=""; MTX_STABLE_CAMUNDA=""
MATRIX_HTML=$(curl -sSL --max-time 10 --fail "${VERSION_MATRIX_URL}" 2>/dev/null || true)

if [[ -n "${MATRIX_HTML}" ]]; then
  # The page lists minors newest-first, each as an <h2> followed by a <table>/<tbody> of
  # chart releases (newest release first within that table). The first <h2> mentioning
  # "Alpha" is the latest alpha minor; the first <h2> mentioning "Standard support until"
  # is the latest stable minor. Their tables' first <tr> is each one's latest chart release.
  MATRIX_VARS=$(printf '%s\n' "${MATRIX_HTML}" | awk '
    function strip(s) { gsub(/<[^>]*>/, "", s); gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
    BEGIN { mode=""; alpha_done=0; stable_done=0; in_tbody=0; in_tr=0 }
    {
      line=$0
      if (line ~ /<h2 /) {
        htext=strip(line)
        in_tbody=0; in_tr=0
        if (!alpha_done && htext ~ /Alpha/) { mode="ALPHA" }
        else if (!stable_done && htext ~ /Standard support until/) {
          mode="STABLE"
        }
        else { mode="" }
        next
      }
      if (mode=="") next
      if (line ~ /<tbody>/) { in_tbody=1; next }
      if (line ~ /<\/tbody>/) { in_tbody=0; mode=""; next }
      if (in_tbody && line ~ /<tr>/) { in_tr=1; tdcount=0; td1=""; td2=""; next }
      if (in_tr && line ~ /<td>/) {
        tdcount++
        val=strip(line)
        if (tdcount==1) td1=val; else if (tdcount==2) td2=val
        next
      }
      if (in_tr && line ~ /<\/tr>/) {
        if (mode=="ALPHA" && !alpha_done) { alpha_chart=td1; alpha_camunda=td2; alpha_done=1 }
        else if (mode=="STABLE" && !stable_done) { stable_chart=td1; stable_camunda=td2; stable_done=1 }
        in_tr=0; mode=""
        next
      }
    }
    END {
      print "MTX_ALPHA_CHART=" alpha_chart
      print "MTX_ALPHA_CAMUNDA=" alpha_camunda
      print "MTX_STABLE_CHART=" stable_chart
      print "MTX_STABLE_CAMUNDA=" stable_camunda
    }
  ')

  # Parsed line-by-line (never eval'd) since MATRIX_VARS ultimately derives from fetched web
  # content - keep it inert even if the page's markup ever changed unexpectedly.
  while IFS='=' read -r mtx_key mtx_value; do
    case "${mtx_key}" in
      MTX_ALPHA_CHART) MTX_ALPHA_CHART="${mtx_value}" ;;
      MTX_ALPHA_CAMUNDA) MTX_ALPHA_CAMUNDA="${mtx_value}" ;;
      MTX_STABLE_CHART) MTX_STABLE_CHART="${mtx_value}" ;;
      MTX_STABLE_CAMUNDA) MTX_STABLE_CAMUNDA="${mtx_value}" ;;
    esac
  done <<< "${MATRIX_VARS}"
fi

echo "Select the version of Camunda you would like to install"
if [[ -n "${MTX_ALPHA_CHART}" && -n "${MTX_ALPHA_CAMUNDA}" && -n "${MTX_STABLE_CHART}" && -n "${MTX_STABLE_CAMUNDA}" ]]; then
  echo "  1) Latest alpha  : Camunda ${MTX_ALPHA_CAMUNDA}  (Helm chart ${MTX_ALPHA_CHART})"
  echo "  2) Latest stable : Camunda ${MTX_STABLE_CAMUNDA}  (Helm chart ${MTX_STABLE_CHART})"
  echo "  3) Enter versions manually"
  echo ""
  read -p "Choose an option (default: 2): " input_version_choice
  version_choice=${input_version_choice:-2}
else
  echo "  Could not fetch/parse ${VERSION_MATRIX_URL} - enter versions manually below."
  version_choice=3
fi

case "${version_choice}" in
  1)
    CAMUNDA_APP_VERSION="${MTX_ALPHA_CAMUNDA}"
    HELM_CHART_VERSION="${MTX_ALPHA_CHART}"
    ;;
  2)
    CAMUNDA_APP_VERSION="${MTX_STABLE_CAMUNDA}"
    HELM_CHART_VERSION="${MTX_STABLE_CHART}"
    ;;
  *)
    read -p "Enter Helm chart version. See ${VERSION_MATRIX_URL} (default: ${DEFAULT_HELM_CHART_VERSION}): " input_helm_version
    HELM_CHART_VERSION=${input_helm_version:-$DEFAULT_HELM_CHART_VERSION}

    read -p "Enter Camunda application version (default: ${DEFAULT_CAMUNDA_APP_VERSION}): " input_app_version
    CAMUNDA_APP_VERSION=${input_app_version:-$DEFAULT_CAMUNDA_APP_VERSION}
    ;;
esac

echo "============================================================"
echo " Camunda configuration"
echo "============================================================"
echo ""

read -p "Enter Camunda domain (default: ${DEFAULT_CAMUNDA_DOMAIN}): " input_domain
CAMUNDA_DOMAIN=${input_domain:-$DEFAULT_CAMUNDA_DOMAIN}

read -p "Enter password (default: ${DEFAULT_PASSWORD}): " input_password
PASSWORD=${input_password:-$DEFAULT_PASSWORD}

ZEEBE_DOMAIN="zeebe.${CAMUNDA_DOMAIN}"

read -p "Exposed to internet, behind a reverse proxy? Choose false when you are not sure. (default: ${DEFAULT_BEHIND_REVERSE_PROXY}): " input_reverse_proxy
BEHIND_REVERSE_PROXY=${input_reverse_proxy:-$DEFAULT_BEHIND_REVERSE_PROXY}

echo ""
echo "============================================================"
echo " Keycunda: first (admin) user"
echo "============================================================"
echo ""

read -p "Enter the first user's name (default: ${DEFAULT_DEMO_NAME}): " input_demo_name
DEMO_NAME=${input_demo_name:-$DEFAULT_DEMO_NAME}

read -p "Enter the first user's email - this is what they log in with (default: ${DEFAULT_DEMO_EMAIL}): " input_demo_email
DEMO_EMAIL=${input_demo_email:-$DEFAULT_DEMO_EMAIL}

echo "  (the first user's password is the password entered above)"


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
export KEYCUNDA_IMAGE="${KEYCUNDA_IMAGE}"
export DEMO_NAME="${DEMO_NAME}"
export DEMO_EMAIL="${DEMO_EMAIL}"
export ES_VERSION="${ES_VERSION}"
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
