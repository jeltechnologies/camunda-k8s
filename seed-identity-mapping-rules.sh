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
echo "Seeding Identity mapping rule for baseline demo user access"
echo "=================================================================="

# camunda-identity creates its default roles (Web Modeler, Console, Optimize, Orchestration,
# ManagementIdentity, ...) synchronously at startup, before its readiness probe passes - so
# waiting for the rollout here is enough to guarantee those rows already exist below.
echo "Waiting for camunda-identity to be ready..."
microk8s kubectl rollout status deployment/camunda-identity -n "${NAMESPACE}" --timeout=5m

# Without this, only the single admin seeded by DEMO_EMAIL (global.identity.auth.identity
# .initialClaimName/initialClaimValue in template-values-camunda-8.9.yaml) gets any role in Identity's
# own authorization store - every other user added later gets "Access denied to organization ..."
# / "Could not fetch your shared resources" in Web Modeler, since that's what it actually checks
# via its identity.base-url call (separate from orchestration.security.authorizations, which is
# disabled). Identity's mapping-rule engine has no "match everyone" operator (only EQUALS/CONTAINS
# on a specific claim), so this keys off the "demo_user" claim that
# keycunda's AuthorizationServerConfig stamps "true" on every human login -
# the closest equivalent to a blanket grant that the schema allows.
echo "Ensuring the 'AllUsers' mapping rule exists..."
pg_identity <<'SQL'
INSERT INTO mapping_rules (type, name, claim_name, claim_value, operator)
VALUES ('ROLE', 'AllUsers', 'demo_user', 'true', 'EQUALS')
ON CONFLICT DO NOTHING;

INSERT INTO mapping_rules_applied_roles (mapping_rules_name, applied_roles_name) VALUES
  ('AllUsers', 'Web Modeler'),
  ('AllUsers', 'Web Modeler Admin'),
  ('AllUsers', 'Console'),
  ('AllUsers', 'Optimize'),
  ('AllUsers', 'Orchestration')
ON CONFLICT DO NOTHING;
SQL

echo "Done."
