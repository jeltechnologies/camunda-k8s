#!/usr/bin/env bash
set -euo pipefail

source ./install-env.sh

NAMESPACE="camunda"
POSTGRES_POD="camunda-postgresql-0"

# Runs psql as the identity user with the SQL on stdin. The password is read inside the pod from
# its own environment (PG_PASSWORD_IDENTITY, from camunda-credentials), so it never appears on a
# command line and this script needs no password of its own.
pg_identity() {
  microk8s kubectl exec -i -n "${NAMESPACE}" "${POSTGRES_POD}" -- \
    bash -c 'PGPASSWORD="${PG_PASSWORD_IDENTITY}" exec psql -U identity -d identity -v ON_ERROR_STOP=1 "$@" -f -' _ "$@"
}

echo "=================================================================="
echo "Granting ManagementIdentity to every M2M (client_credentials) client"
echo "=================================================================="

# Requires keycunda's AuthorizationServerConfig to stamp `m2m_client=true`
# on every client_credentials token (added alongside the existing "demo_user=true" stamped on
# every human login) - without that claim, this rule matches nothing. Same pattern as the
# "AllUsers" rule in seed-identity-mapping-rules.sh, just for M2M clients instead of human users.
pg_identity <<'SQL'
INSERT INTO mapping_rules (type, name, claim_name, claim_value, operator)
VALUES ('ROLE', 'AllM2MClients', 'm2m_client', 'true', 'EQUALS')
ON CONFLICT DO NOTHING;

INSERT INTO mapping_rules_applied_roles (mapping_rules_name, applied_roles_name)
VALUES ('AllM2MClients', 'ManagementIdentity')
ON CONFLICT DO NOTHING;
SQL

echo "Done."
