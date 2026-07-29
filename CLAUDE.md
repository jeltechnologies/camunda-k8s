# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Bash + Kubernetes manifests that install a complete Camunda 8 platform (Orchestration/Zeebe,
Operate, Tasklist, Web Modeler, Optimize, Console, Identity, Connectors) onto a **single Ubuntu
box** running MicroK8s, fronted by nginx ingress with OIDC authentication provided by a small,
purpose-built identity provider on real path-based URLs.

Almost everything here is shell scripts and YAML templates — no build, no test suite, no CI for
those. **One deliberate exception:** `camunda-demo-identity-provider/` is real application source
code (Spring Boot, Maven build, GitHub Actions CI publishing to GHCR). It replaces Keycloak as the
platform's OIDC provider. See its own `README.md` for how to build/run/test it.

Target audience is demos, learning and experimenting — explicitly not production (see README).
The identity provider is explicitly **demo-grade**, not an enterprise-security Identity Provider: no MFA, no
audit logging, no key rotation, single in-memory JWT signing key regenerated on every restart. Do
not present it as a production-hardened component.

## Repository layout

| File | Role |
|---|---|
| `1-install-microk8s.sh` | Host prep: apt upgrade, swapoff, MicroK8s 1.32, addons, kubectl config, Helm v4. Run once, then reboot. |
| `2-install-camunda-microk8s.sh` | The main orchestrator. Everything else is called from here. |
| `configure-env.sh` | Interactive wizard; **writes `install-env.sh`** (the generated config). |
| `.env.example` | Documents the shape of `install-env.sh` with placeholder values — reference only, not consumed by any script. |
| `install-fix-hosts.sh` | Adds `/etc/hosts` entries (skipped when behind a reverse proxy). Needs root. |
| `create-certifcate.sh` | Self-signed TLS cert → `tls-secret-<domain>` k8s secret. (Filename typo is intentional//historic — do not "fix" it without updating callers.) |
| `template-*.yaml` | `envsubst` input templates — **the only YAML you should edit**. |
| `camunda-demo-identity-provider/` | Spring Boot OIDC identity provider — source, Maven build, own README. |
| `.github/workflows/build-camunda-demo-identity-provider.yml` | Builds/pushes its image to GHCR on push to `main` or an `identity-provider-v*` tag. |
| `update-connector-secrets.sh` | Applies optional `connector-secrets.yaml`, patches the connectors Deployment with `envFrom`, restarts the pod. |
| `seed-identity-mapping-rules.sh` | Idempotently grants every demo user (via Identity's "AllUsers" mapping rule) baseline Web Modeler/Console/Optimize/Orchestration access — see "Identity's own authorization store needs its own bootstrap, twice over" in Architecture facts below. |
| `grant-webmodeler-public-api-access.sh <client-id>` | Idempotently grants one admin-managed M2M client (created via camunda-demo-identity-provider's `/admin/clients`, not a fixed Camunda-component client) full CRUD access to Web Modeler's public API, via the same claim-matching mapping-rule mechanism as `seed-identity-mapping-rules.sh` — see the same "Identity's own authorization store..." note. |
| `tail-connector-logs.sh` | Convenience log tail for the connectors pod. |

## The templating model — most important thing to know

Generated files are **gitignored and overwritten on every install**. Never edit them:

```
template-values-camunda.yaml                    --envsubst-->  values-camunda.yaml     (gitignored)
template-elasticsearch.yaml                     --envsubst-->  piped straight to kubectl apply
template-postgresql.yaml                        --envsubst-->  piped straight to kubectl apply
template-camunda-demo-identity-provider.yaml    --envsubst-->  piped straight to kubectl apply
template-camunda-demo-identity-provider-ingress.yaml
template-volumes.yaml
configure-env.sh                                --writes-->    install-env.sh          (gitignored)
```

`values-camunda.yaml` is present in the working tree but ignored — treat it as build output when
diagnosing something, not as a file to change.

### Adding a new variable requires three edits

`envsubst` is called with an **explicit allow-list**; any `${VAR}` not in the list is passed
through literally into the manifest and will silently break the deploy. To add a variable:

1. `configure-env.sh` — add the `DEFAULT_*`, the `read -p` prompt (if user-facing), the
   "reuse previous values" branch at the top, **and** the `export` line in the `install-env.sh`
   heredoc at the bottom.
2. `2-install-camunda-microk8s.sh` — add `${VAR}` to the `envsubst '...'` allow-list for the
   template that consumes it.
3. The `template-*.yaml` itself.

Current allow-lists in `2-install-camunda-microk8s.sh`:
- elasticsearch: `${ES_VERSION}`
- postgresql: `${PASSWORD} ${PG_VERSION}`
- camunda-demo-identity-provider: `${IDENTITY_PROVIDER_IMAGE} ${CAMUNDA_DOMAIN} ${PASSWORD} ${DEMO_NAME} ${DEMO_EMAIL}`
- camunda-demo-identity-provider-ingress: `${CAMUNDA_DOMAIN}`
- volumes: `${HOME}`
- camunda values: `${CAMUNDA_DOMAIN} ${ZEEBE_DOMAIN} ${CAMUNDA_APP_VERSION} ${OLLAMA_ENABLED} ${OLLAMA_MODEL} ${OLLAMA_URL} ${GITLAB_URL} ${SWAGGER_ENABLED}`

## Install flow (`2-install-camunda-microk8s.sh`)

1. `./configure-env.sh` then `source ./install-env.sh`
2. Assert Helm major version ≥ 4 (hard exit otherwise)
3. `sudo ./install-fix-hosts.sh`
4. Add/update the `camunda` Helm repo
5. Create `nginx-ingress-tcp-microk8s-conf` ConfigMap in the **`ingress`** namespace mapping TCP
   `26500 → camunda/camunda-zeebe-gateway:26500` (this is how Zeebe gRPC gets exposed — there are
   no NodePorts anywhere)
6. Create `camunda` namespace, self-signed certs for `$CAMUNDA_DOMAIN` and `$ZEEBE_DOMAIN`
7. Create the `camunda-credentials` secret imperatively — all keys get the same `$PASSWORD`.
   Every template refers to it via `existingSecret`/`existingSecretKey`; no password is ever
   templated directly into the Helm values.
8. Apply Elasticsearch → PostgreSQL → camunda-demo-identity-provider, waiting on `rollout status`
   for each
9. `helm uninstall camunda || true`, delete the connectors PV/PVC, regenerate values, recreate
   host dirs + PVs, `helm install camunda camunda/camunda-platform --wait --timeout 20m`
10. `./update-connector-secrets.sh`
11. `./seed-identity-mapping-rules.sh`

Re-running is the supported upgrade path: only the Helm release is torn down. The Elasticsearch,
PostgreSQL and identity-provider Deployment and their PVCs/state are `apply`-ed in place, so
**data survives**. The connectors PV/PVC is deliberately deleted and recreated each run.

## Conventions to follow

- **`microk8s kubectl`, never bare `kubectl`.** Scripts must work in the same shell that ran
  `1-install-microk8s.sh` (the bare `kubectl` alias only exists after a new login shell).
- **Run from the repo root.** All paths are relative (`./install-env.sh`, `./create-certifcate.sh`).
- `set -euo pipefail` at the top of every script; `|| true` on the calls that are allowed to fail.
- Loud banner-style progress output (`echo ====...`) between phases — match the existing style.
- Waits are explicit `kubectl rollout status`/`kubectl wait` with a timeout, never bare `sleep`.
- Version pins live as constants at the top of `configure-env.sh`
  (`ES_VERSION`, `PG_VERSION`, `IDENTITY_PROVIDER_IMAGE`) and as `DEFAULT_*` prompts for the Camunda Helm chart
  and app version. `IDENTITY_PROVIDER_IMAGE` is deliberately **not** prompted, unlike the Camunda-side versions
  — the identity provider is expected to need no end-user tuning once it's running, so bumping it
  is a code change (this constant), not something to surface in the wizard. If you bump any of
  these, **update the Versions table in `README.md` to match** — it is maintained by hand.

## Architecture facts that are easy to get wrong

- **Two datastores, different jobs.** Orchestration uses the **RDBMS** secondary storage
  (`jdbc:postgresql://camunda-postgresql:5432/orchestration`, `exporters.rdbms.enabled: true`,
  `exporters.camunda.enabled: false`). Elasticsearch is there for **Optimize**. Don't assume the
  usual ES-backed Zeebe exporter setup.
- **One PostgreSQL, four databases**, all created by the init ConfigMap in
  `template-postgresql.yaml`: `camunda-demo-identity-provider` (user `camunda-demo-identity-provider`,
  matching the image name), `web-modeler` (user `webmodeler`), `orchestration` (user
  `orchestration`), and `identity` (user `identity` — Management Identity's own JPA store,
  needed only in GENERIC OIDC mode; see the note under `identity.externalDatabase` in
  `template-values-camunda.yaml`). Adding a database means editing that init script — it only
  runs on a **fresh** PG data volume, so an existing install needs manual SQL.
- **camunda-demo-identity-provider replaced Keycloak, and runs in the `camunda` namespace**
  alongside the rest of the platform, as a plain `kubectl apply`-ed Deployment/Service (not part
  of the Helm release — `helm uninstall camunda` never touches it). It was originally split into
  its own `jeltechnologies` namespace to model "external component this stack depends on", but
  that bought cross-namespace DNS footguns (a component reaching it internally has to remember the
  `.jeltechnologies` suffix everywhere) for no real benefit in a single-box demo, and it caused a
  real bug: Web Modeler's backend fetching JWKS over cluster DNS threw `UnknownHostException`.
  Co-locating it in `camunda` means the short Service name (`camunda-demo-identity-provider`)
  resolves correctly everywhere, it reads client secrets straight from `camunda-credentials`
  (including its own Postgres password, key `identity-provider-postgresql-password`) instead of a
  duplicate `identity-provider-credentials` Secret, and its Ingress reuses the same
  `tls-secret-${CAMUNDA_DOMAIN}` instead of a second copy of the cert.

  It runs as a single replica now (`template-camunda-demo-identity-provider.yaml`, `replicas: 1`)
  — the multi-replica setup was for testing future multi-node deployments, not a current need.
  The JDBC-backed session/token store described below still matters even at replicas: 1: it's what
  makes a pod restart (redeploy, node reschedule) not silently log everyone out, since sessions
  and tokens survive in Postgres rather than in-process memory.

  It has no volume of its own — all state is either in Postgres or in the
  `camunda-identity-provider-signing-key` Secret, specifically so it can run multiple replicas and
  survive restarts without logging everyone out:
  - **Users** live in the `camunda-demo-identity-provider` Postgres database (`users` table).
    **Email is the unique login identifier; `name` is free text and not unique.** Users log in
    with email + password. The `preferred_username` claim carries the email (Camunda's own RBAC
    keys off this — see `orchestration.security.initialization` in
    `template-values-camunda.yaml`); a separate `name` claim carries the free-text display name,
    looked up fresh per token so admin-screen edits show up without a new login.
  - **HTTP sessions** and **issued tokens** (auth codes, access/refresh tokens) are also in that
    same database via `spring-session-jdbc` and `JdbcOAuth2AuthorizationService` — not the
    in-memory defaults — so a request doesn't need to land on the same pod that handled the
    previous one in the flow.
  - **The RSA JWT signing key** comes from the `camunda-identity-provider-signing-key` Secret
    (`IDENTITY_PROVIDER_JWT_SIGNING_KEY_PEM`), generated once by `2-install-camunda-microk8s.sh` (idempotent —
    it checks whether the secret already exists before generating) rather than freshly per pod
    startup. Every replica must sign/validate with the *same* key, and a restart must not rotate
    it, or outstanding tokens stop validating. Local dev without that env var falls back to a
    fresh ephemeral key, which is fine for exactly one instance.
  - The JDBC schema for issued tokens is `oauth2-authorization-schema-postgresql.sql`, adapted by
    hand from the schema shipped inside `spring-security-oauth2-authorization-server` (its
    `blob`/`timestamp` columns don't work on Postgres — see the comment in that file — and
    `TIMESTAMP WITH TIME ZONE` was used instead of Postgres's `timestamptz` shorthand so the same
    file also works against H2 in tests).

  Camunda is configured with `global.identity.auth.type: "GENERIC"` (see the comment block at the
  top of that section in
  `template-values-camunda.yaml`) — a documented, supported Camunda 8.8+ mode for connecting to any
  standards-compliant external OIDC provider instead of the chart's bundled Keycloak. Camunda's own
  `identity` component still manages authorization/roles in its own DB; only *authentication*
  moved. The OAuth2 client set (`camunda-identity`, `orchestration`, `connectors`, `optimize`,
  `web-modeler`, `console`) is fixed in `camunda-demo-identity-provider/.../OidcClientsConfig.java`
  — adding a new Camunda component means a code change there **and** a matching block in
  `template-values-camunda.yaml`, cross-checked against `helm show values camunda/camunda-platform
  --version <HELM_CHART_VERSION>` since field names have moved between chart versions (e.g.
  `orchestration.security.authentication.oidc.issuer`, not `issuerUrl`). **Connectors is its own
  client, not a reuse of `orchestration`'s** — `helm template` was needed to find this, since
  neither the docs nor the values.yaml comments call it out: Identity's rendered
  `camunda-connectors-configuration` ConfigMap hardcodes `client-id: "connectors"`. **M2M clients
  accept both `client_secret_basic` and `client_secret_post`** in `OidcClientsConfig.java` —
  Camunda's Java client SDK sends credentials in the token request body, not an `Authorization`
  header; confirmed by testing both directly against `/oauth2/token` when Connectors' M2M auth
  kept failing with 401 despite the secret value being verified correct.
  **That fixed set is separate from user-managed "Clients"** — client-credentials-only OAuth2
  clients an admin creates/deletes at `/admin/clients` (`camunda-demo-identity-provider/.../client/`
  and `web/AdminClientController.java`), stored in the `oauth_clients` table, for external
  automation talking to Camunda's APIs — the equivalent of what Camunda's own Console calls
  "Clients" from 8.9 onward (formerly "M2M"). `CompositeRegisteredClientRepository` is the actual
  `RegisteredClientRepository` bean; it checks the fixed set first (renamed to the
  `fixedRegisteredClientRepository` bean in `OidcClientsConfig`) and falls through to the database
  for anything else, so a user-managed client can never shadow a fixed one, and
  `AdminClientController` separately rejects the fixed clients' IDs at creation time so one can't
  be created un-reachable. This app only issues the client's token; granting it roles/permissions
  is done in Camunda Identity, same as for human users.

  Each such client has its own `audience` column, editable at `/admin/clients/{id}/edit`, which
  `AuthorizationServerConfig.dynamicClientAudiences` stamps into that client's tokens as `aud` —
  without it, a client created here only ever gets its own client ID as audience, and every
  resource server (Orchestration's REST API included) rejects the token as wrong-audience. Set it
  to `orchestration-api` for a client that needs to call Orchestration, matching what the fixed
  `orchestration`/`connectors` clients get from `identity-provider.clients.orchestration.audience`.
  Also unlike user passwords, a client's plaintext secret **is** kept (the `secret` column,
  alongside `secret_hash`) and stays visible on the clients list/edit page indefinitely — a
  deliberate demo-grade choice, since these secrets need to be pasted into whatever external system
  authenticates as that client.
- **Identity's own authorization store needs its own bootstrap, twice over.** `global.identity
  .auth.identity.initialClaimName`/`initialClaimValue` in `template-values-camunda.yaml` (set to
  `preferred_username`/`${DEMO_EMAIL}`) is what makes Management Identity create a "Default"
  mapping rule for the one seeded admin — without it, Identity's own `mapping_rules` table stays
  empty forever (its default `initialClaimName` is `"oid"`, a Microsoft Entra claim our tokens
  never carry), and *no* user has *any* role there, which Web Modeler's `identity.base-url` check
  surfaces as "Access denied to organization ..." / "Could not fetch your shared resources" in the
  UI — found by decoding the actual claims Identity has to work with and cross-checking against
  the chart's own commented generic-OIDC example. That bootstrap only covers the one admin,
  though: Identity has no "match everyone" mapping-rule operator (only `EQUALS`/`CONTAINS` on a
  specific claim+value), so `seed-identity-mapping-rules.sh` separately creates an "AllUsers" rule
  keyed on `demo_user=true` — a claim `AuthorizationServerConfig`'s token customizer stamps on
  every human login specifically so this rule has something constant to match — granting baseline
  Web Modeler/Console/Optimize/Orchestration roles to any user, not just the bootstrap admin. Runs
  after `helm install` in `2-install-camunda-microk8s.sh`; idempotent, safe to re-run.

  **Same gap, but for M2M clients.** Identity's "Applications" concept only covers the fixed,
  Helm-configured client set (`camunda-identity`, `orchestration`, `connectors`, `optimize`,
  `web-modeler`, `console`) — it has no awareness of clients created via
  camunda-demo-identity-provider's own `/admin/clients` (see the `client` package and
  `CompositeRegisteredClientRepository`), so such a client can never be found or granted a role
  through Identity's own console UI. The fix is the same claim-matching mapping-rule mechanism as
  above: `grant-webmodeler-public-api-access.sh <client-id>` creates a "Web Modeler Public API"
  role (CRUD permissions on the `web-modeler-public-api` audience — the actual external REST API,
  not `web-modeler-api`, which is only the internal one the Web Modeler SPA itself uses and has no
  CRUD-level permissions defined at all) and a mapping rule keyed on `preferred_username` equal to
  the client ID, since `AuthorizationServerConfig`'s token customizer stamps that claim on M2M
  tokens too (client ID standing in for the human email). Also requires that client's own
  `audience` (set via `/admin/clients`) to include `web-modeler-public-api`, or its tokens won't
  carry the right `aud` and Web Modeler rejects them regardless of this grant — found by querying
  Identity's own Postgres `permissions`/`roles_permissions` tables directly, since there's no UI
  for a client Identity doesn't know about.
- **Ingress-behind-proxy pitfalls.** Everything is path-routed on one host, TLS-terminated at
  nginx. Components must therefore be told their context path *and* to trust `X-Forwarded-*`.
  The Web Modeler `forward-headers-strategy: native` block in `template-values-camunda.yaml`
  documents one such fix (Tomcat + `https-only: true` → redirect loop); expect similar issues
  whenever a component is upgraded and read that comment before touching it.
  `camunda-demo-identity-provider` sets the same `server.forward-headers-strategy=native` for the
  identical reason.
- **Host-path volumes.** `~/camunda-docs` (document store, mounted at `/camunda-docs` in
  identity/optimize/connectors/orchestration) and `~/camunda-connectors` (custom connector JARs,
  mounted at `/opt/custom` with `LOADER_PATH=/opt/custom/connectors`). Both are `hostPath` PVs
  templated with `${HOME}`, so they are tied to the installing user's home directory.
- **`SWAGGER_ENABLED` defaults to `false` on purpose** — it exposes the full REST API surface
  unauthenticated. Do not flip the default; it is called out as a security note in the README.

## Verifying changes

No test harness exists for the shell/YAML side. Reasonable checks before declaring a change good:

```bash
bash -n <script>.sh                                       # syntax
shellcheck <script>.sh                                    # if available
source ./install-env.sh && envsubst '${VAR} ...' < template-x.yaml   # inspect rendered YAML
microk8s kubectl apply --dry-run=server -f -               # validate against the API server
microk8s kubectl get pods -n camunda -w
./tail-connector-logs.sh
```

For `camunda-demo-identity-provider/`, there **is** a real build to verify:

```bash
cd camunda-demo-identity-provider && mvn -q verify         # compiles + runs the test suite
```

A full install takes 15–20 minutes and mutates the host (apt, swap, `/etc/hosts`, snap), so
**never run `1-install-microk8s.sh` or `2-install-camunda-microk8s.sh` speculatively** — render
and inspect instead, and let the user run the installers on their VM.

## Secrets hygiene

`install-env.sh`, `values-camunda.yaml`, `connector-secrets.yaml` and the rendered manifests all
contain the plaintext `$PASSWORD` and are gitignored for that reason. Don't commit them, don't
echo their contents into a summary, and don't add generated filenames to git. Note that
`2-install-camunda-microk8s.sh` and `configure-env.sh` intentionally print the password to the
terminal during install — that's existing behaviour for a single-user demo box, not a bug to fix
unasked. The `camunda-credentials` secret's keys (`identity-identity-client-token`,
`identity-orchestration-client-token`, `identity-optimize-client-token`,
`identity-connectors-client-token`, `webmodeler-postgresql-user-password`,
`orchestration-postgresql-password`, `identity-provider-postgresql-password`) are all set to the
same `$PASSWORD` too, doing double duty as both database passwords and OAuth2 client secrets for
`camunda-demo-identity-provider`. The
**`camunda-identity-provider-signing-key` Secret is deliberately separate** and handled differently: unlike
`camunda-credentials` (deleted and recreated on every install run, harmless since the value always
comes from the same `$PASSWORD`), this one holds randomly generated key material with no external
source of truth, so `2-install-camunda-microk8s.sh` only creates it if it doesn't already exist.
Never change that to an unconditional delete-and-recreate — it would silently invalidate every
outstanding token on the next install run.

**`.env` / `.env.example` convention.** Every place secrets live has a matching `.example` file
committed alongside it, documenting the shape with placeholder values only:
- `.env.example` (root) mirrors `install-env.sh` — reference only; `configure-env.sh` is still
  the actual way to produce `install-env.sh`, nobody should hand-write it from the example.
- `camunda-demo-identity-provider/.env.example` mirrors the `.env` a developer creates for local
  `mvn spring-boot:run` (see that subproject's README).

Both real `.env` files are gitignored. When adding a new secret-bearing variable anywhere, add it
to the relevant `.example` file in the same change — an `.example` file that's drifted out of sync
with what the scripts/app actually read is worse than no example at all.
