# Granting an M2M client Web Modeler public-API access

How to fix `HTTP 404 {"title":"Not Found",...,"instance":"/modeler/api/v1/..."}` for an M2M
(client_credentials) client that should be able to call Web Modeler's public REST API
(`/modeler/api/v1/*` — used for backup/restore and connector template publishing).

## Root cause #1: no matching claim for Identity's Mapping Rules

Management Identity's "Add mapping" UI only lets you pick `username` / `sub` / `groups` / `roles`
as the claim name. `camunda-demo-identity-provider` only ever stamped `preferred_username` on
tokens (not `username`), and `sub` alone was observed to not get matched by Identity's mapping-rule
evaluation either — so an M2M client could never be granted a role through that UI at all.

**Fix**: `AuthorizationServerConfig.tokenCustomizer()` now also stamps a `username` claim
(identical value to `preferred_username`) on every token. Ship this before touching anything below
— nothing else here works until the client's token actually carries a claim Identity can match on.

## Root cause #2: the role that's needed usually doesn't exist yet

The built-in `Web Modeler` role only grants the *internal client* API (`web-modeler-api`
audience — what the browser SPA uses). It does **not** cover the *public* API
(`web-modeler-public-api` audience) that `/modeler/api/v1/*` checks for M2M callers. A fresh
cluster has no role for that audience at all — you have to create one.

### The role solution

Once the client has *any* role that includes `ManagementIdentity` (see the caution below), its own
token can call Identity's REST API directly to create everything needed — no direct DB writes:

```bash
TOKEN=<a client_credentials token for a client with ManagementIdentity access>
HOST=https://<your-domain>

# 1. Create the role
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Web Modeler Public API","description":"Grants CRUD access to the Web Modeler public API, for M2M applications"}' \
  "$HOST/identity/api/roles"

# 2. Attach CRUD permissions for the web-modeler-public-api resource server
for def in create:\* read:\* update:\* delete:\*; do
  curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"definition\":\"$def\",\"resourceServerId\":\"web-modeler-public-api\"}" \
    "$HOST/identity/api/roles/Web%20Modeler%20Public%20API/permissions"
done

# 3. Add the role to the target client's mapping rule.
# NOTE the API field is `appliedRoleNames`, not `appliedRoles` (the GET response uses `appliedRoles`
# with expanded objects, but PUT/POST want plain name strings under `appliedRoleNames` -
# get this wrong and the server 500s with a NullPointerException on `appliedRoleNames`).
curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"<mapping-rule-name>","claimName":"username","claimValue":"<client-id>","operator":"EQUALS","type":"ROLE","appliedRoleNames":["Web Modeler Public API"],"appliedTenantIds":[]}' \
  "$HOST/identity/api/mapping-rules/<mapping-rule-name>"
```

### Verify

```bash
curl -H "Authorization: Bearer $TOKEN" "$HOST/modeler/api/v1/info"
# createPermission/readPermission/updatePermission/deletePermission should all be true

curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' \
  "$HOST/modeler/api/v1/projects/search"
# 200 {"items":[...],"total":N} instead of 404
```

## Caution: how the client got `ManagementIdentity` in the first place matters

The steps above require a token that already has `ManagementIdentity`. On the local test cluster,
that was granted by adding an `m2m_client=true` claim (stamped on every M2M token) plus an
`AllM2MClients` mapping rule granting `ManagementIdentity` to **every** client_credentials client,
not just the one that needed Web Modeler access. That's broad — any client with `ManagementIdentity`
can rewrite the *entire* authorization model (roles, permissions, mapping rules, users, groups,
tenants), not just Web Modeler's. It was done deliberately for local-cluster iteration speed.

Before repeating that pattern on a real/shared server: prefer granting `ManagementIdentity` to a
specific client only for as long as it takes to run the steps above, then scope it back down to
just `Web Modeler Public API` — don't leave the blanket `AllM2MClients` → `ManagementIdentity`
mapping in place on anything other than a throwaway test cluster.
