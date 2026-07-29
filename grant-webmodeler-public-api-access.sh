#!/usr/bin/env bash
set -euo pipefail

source ./install-env.sh

NAMESPACE="camunda"
POSTGRES_POD="camunda-postgresql-0"

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <client-id>" >&2
    echo "Grants an M2M client (one created via camunda-demo-identity-provider's /admin/clients," >&2
    echo "not one of the fixed Camunda-component clients) full CRUD access to Web Modeler's public API." >&2
    exit 1
fi

CLIENT_ID="$1"
MAPPING_RULE_NAME="${CLIENT_ID}-m2m"

echo "=================================================================="
echo "Granting Web Modeler public-API CRUD access to client \"${CLIENT_ID}\""
echo "=================================================================="

# The role/permission rows are shared across every client granted this access; only the mapping
# rule below is per-client. Identity has no "grant this specific client X" concept and no
# "Applications" list for clients outside the fixed Camunda-component set (see OidcClientsConfig.java)
# - the only mechanism is a claim-matching mapping rule, same as seed-identity-mapping-rules.sh's
# "AllUsers"/"demo_user" rule. camunda-demo-identity-provider's AuthorizationServerConfig stamps
# `preferred_username` = the client ID on every M2M (client_credentials) token (the same claim used
# for human logins), so that's what this matches on.
#
# This alone isn't enough: the client's own `audience` (set via camunda-demo-identity-provider's
# /admin/clients) must also include "web-modeler-public-api", or its tokens won't carry the right
# `aud` and Web Modeler will still reject them with 401 regardless of this grant.
microk8s kubectl exec -n "${NAMESPACE}" "${POSTGRES_POD}" -- env PGPASSWORD="${PASSWORD}" psql -U identity -d identity \
    -v ON_ERROR_STOP=1 -v client_id="${CLIENT_ID}" -v mapping_rule_name="${MAPPING_RULE_NAME}" -c "
INSERT INTO roles (name, description)
VALUES ('Web Modeler Public API', 'Grants CRUD access to the Web Modeler public API, for M2M applications')
ON CONFLICT DO NOTHING;

INSERT INTO roles_permissions (role_entity_name, permissions_id)
SELECT 'Web Modeler Public API', id FROM permissions WHERE audience = 'web-modeler-public-api'
ON CONFLICT DO NOTHING;

INSERT INTO mapping_rules (type, name, claim_name, claim_value, operator)
VALUES ('ROLE', :'mapping_rule_name', 'preferred_username', :'client_id', 'EQUALS')
ON CONFLICT DO NOTHING;

INSERT INTO mapping_rules_applied_roles (mapping_rules_name, applied_roles_name)
VALUES (:'mapping_rule_name', 'Web Modeler Public API')
ON CONFLICT DO NOTHING;
"

echo "Done."
