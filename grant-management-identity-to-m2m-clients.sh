#!/usr/bin/env bash
set -euo pipefail

source ./install-env.sh

NAMESPACE="camunda"
POSTGRES_POD="camunda-postgresql-0"

echo "=================================================================="
echo "Granting ManagementIdentity to every M2M (client_credentials) client"
echo "=================================================================="

# Requires keycunda's AuthorizationServerConfig to stamp `m2m_client=true`
# on every client_credentials token (added alongside the existing "demo_user=true" stamped on
# every human login) - without that claim, this rule matches nothing. Same pattern as the
# "AllUsers" rule in seed-identity-mapping-rules.sh, just for M2M clients instead of human users.
microk8s kubectl exec -n "${NAMESPACE}" "${POSTGRES_POD}" -- env PGPASSWORD="${PASSWORD}" psql -U identity -d identity -v ON_ERROR_STOP=1 -c "
INSERT INTO mapping_rules (type, name, claim_name, claim_value, operator)
VALUES ('ROLE', 'AllM2MClients', 'm2m_client', 'true', 'EQUALS')
ON CONFLICT DO NOTHING;

INSERT INTO mapping_rules_applied_roles (mapping_rules_name, applied_roles_name)
VALUES ('AllM2MClients', 'ManagementIdentity')
ON CONFLICT DO NOTHING;
"

echo "Done."
