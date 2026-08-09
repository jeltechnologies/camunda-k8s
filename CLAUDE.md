# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Bash + Kubernetes manifests that install a complete Camunda 8 platform (Orchestration/Zeebe,
Operate, Tasklist, Web Modeler, Optimize, Console, Identity, Connectors) onto a **single Ubuntu
box** running MicroK8s, fronted by nginx ingress with OIDC authentication provided by a small,
purpose-built identity provider on real path-based URLs.

Almost everything here is shell scripts and YAML templates — no build, no test suite, no CI for
those. **One deliberate exception:** `keycunda/` is real application source
code (Spring Boot, Maven build, GitHub Actions CI publishing to GHCR). It replaces Keycloak as the
platform's OIDC provider. See its own `README.md` for how to build/run/test it.

Target audience is demos, learning and experimenting — explicitly not production (see README).
Keycunda is explicitly **demo-grade**, not an enterprise-security Identity Provider: no MFA, no
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
| `template-values-camunda-8.10.yaml` | 8.10-line (Helm chart 15.x) counterpart to `template-values-camunda.yaml` — see "Two Helm value templates, one per Camunda major line" below. |
| `keycunda/` | Spring Boot OIDC identity provider — source, Maven build, own README. |
| `.github/workflows/build-keycunda.yml` | Builds/pushes its image to GHCR on push to `main` or a `keycunda-v*`/`v*` tag. |
| `seed-identity-mapping-rules.sh` | Idempotently grants every demo user (via Identity's "AllUsers" mapping rule) baseline Web Modeler/Console/Optimize/Orchestration access — see "Identity's own authorization store needs its own bootstrap, twice over" in Architecture facts below. |
| `grant-webmodeler-public-api-access.sh <client-id>` | Idempotently grants one admin-managed M2M client (created via keycunda's `/admin/clients`, not a fixed Camunda-component client) full CRUD access to Web Modeler's public API, via the same claim-matching mapping-rule mechanism as `seed-identity-mapping-rules.sh` — see the same "Identity's own authorization store..." note. |
| `tail-connector-logs.sh` | Convenience log tail for the connectors pod. |

## The templating model — most important thing to know

Generated files are **gitignored and overwritten on every install**. Never edit them:

```
template-values-camunda.yaml       (8.9/chart 14.x)  --envsubst-->  values-camunda.yaml  (gitignored)
template-values-camunda-8.10.yaml  (8.10/chart 15.x+) --envsubst-->  values-camunda.yaml  (gitignored)
template-elasticsearch.yaml                     --envsubst-->  piped straight to kubectl apply
template-postgresql.yaml                        --envsubst-->  piped straight to kubectl apply
template-keycunda.yaml    --envsubst-->  piped straight to kubectl apply
template-keycunda-ingress.yaml
template-volumes.yaml
configure-env.sh                                --writes-->    install-env.sh          (gitignored)
```

`template-keycunda-rbac.yaml` is the one exception: it has no `${VAR}` placeholders at
all (namespace and names are fixed), so it's `kubectl apply`-ed directly, with no `envsubst` step.

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
- keycunda: `${KEYCUNDA_IMAGE} ${CAMUNDA_DOMAIN} ${PASSWORD} ${DEMO_NAME} ${DEMO_EMAIL}`
- keycunda-ingress: `${CAMUNDA_DOMAIN}`
- volumes: `${HOME}`
- camunda values: `${CAMUNDA_DOMAIN} ${ZEEBE_DOMAIN} ${CAMUNDA_APP_VERSION} ${OLLAMA_ENABLED} ${OLLAMA_MODEL} ${OLLAMA_URL} ${GITLAB_URL} ${SWAGGER_ENABLED} ${DEMO_EMAIL} ${DEMO_NAME} ${PASSWORD}` —
  shared by whichever of `template-values-camunda.yaml` / `template-values-camunda-8.10.yaml` gets
  picked (see below), so a new variable needs step 3 done in both files if it's not version-specific.

### Two Helm value templates, one per Camunda major line

`template-values-camunda.yaml` targets the 8.9 chart line (Helm chart 14.x, the default).
`template-values-camunda-8.10.yaml` targets 8.10 (chart 15.x+, currently alpha).
`2-install-camunda-microk8s.sh` picks between them by comparing `HELM_CHART_VERSION`'s major
version against 15 — **not** `CAMUNDA_APP_VERSION` — since the chart's `values.yaml` schema is
what the template actually has to match, and the two version numbers aren't always in lockstep
pre-GA.

The 8.10 file is a **full copy** of the 8.9 one, not a diff/overlay: neither Helm values nor the
identity chart's `identity.configuration` mechanism support merging a partial override into a
nested list/map from a different source (confirmed the hard way — see below). Keep unrelated
changes mirrored between the two files by hand; diverge only where a chart/app version genuinely
requires it, and comment every such spot.

Known differences today, both in `template-values-camunda-8.10.yaml`:
- **Fixed**: `identity.env` carries six `IDENTITY_MAPPINGRULES_0_*` entries working around a
  startup crash in Identity 8.10.0-alpha4.2. Root cause (found by extracting and decompiling the
  running `identity.jar` — `BOOT-INF/classes/application.yaml`, and
  `io.camunda.identity.impl.oidc.initializer.OidcMappingRuleInitializer`/
  `MappingRuleInitializerService`/`AbstractOidcMappingRuleServiceImpl`): the jar's own bundled
  default config unconditionally defines one mapping rule ("OC Cluster Endpoint Access") granting
  the role "Web Modeler Public API - Cluster Ping" — a role that only actually gets created when
  Camunda Hub cluster-ping is enabled. The chart's `identity/configmap.yaml` template correctly
  omits that role when ping is disabled (our case — this is a single-box demo, no Hub
  connectivity) but never correspondingly clears the mapping rule referencing it, so Identity
  crash-loops on every startup with `appliedRoleNames.notFound` — not a race, reproduced
  identically across 7 consecutive restarts. All six `IDENTITY_MAPPINGRULES_0_*` fields must be
  set together (not just the broken `APPLIEDROLENAMES_0`): Spring Boot doesn't merge one field
  from an env var into the classpath YAML's entry at the same list index — once any field at that
  index comes from a higher-priority source, the *entire* element is sourced from there, so a
  partial override leaves the untouched fields null (confirmed — it turned
  `appliedRoleNames.notFound` into a second crash, `IllegalArgumentException: The given id must
  not be null`). The override points the rule at the existing "Orchestration" role instead of the
  missing one — deliberately the least-privileged choice, since it's Orchestration's own service
  account mapping to its own role, adding nothing beyond what that account already has via its
  direct M2M permission grant.
- **Fixed, two parts**: `orchestration.security.authentication.oidc` and `connectors` both used to
  crash-loop with a PKIX path-building failure — a new-in-8.10 code path
  (`io.camunda.security.spring.oidc.ScopedJwtDecoderFactory`/`ScopedClientRegistrationFactory`,
  and `io.camunda.client.impl.oauth.OAuthCredentialsProviderBuilder` for connectors) fetches
  `${issuer}/.well-known/openid-configuration` over public HTTPS. See the comment above
  `orchestration.security.authentication.oidc` in `template-values-camunda-8.10.yaml` for the full
  trace. Two independent things were both required to fix it:
  1. **Trust**: `global.tls.caBundle` (also in `template-values-camunda-8.10.yaml`, top of
     `global:`) — the chart's own purpose-built mechanism for this, not a hand-rolled
     initContainer: reuses each component's own image (already ships `keytool`), rebuilds a
     combined truststore into an `emptyDir`, and wires `JAVA_TOOL_OPTIONS` — all automatic once
     `global.tls.caBundle.secret.existingSecret` is set, confirmed by reading
     `templates/orchestration/statefulset.yaml` / `templates/connectors/deployment.yaml` (both
     call the `caBundleInitContainer`/`caBundleJavaToolOptionsEnv`/`caBundleTruststoreVolumeMount`
     helpers unconditionally, gated only on this being non-empty). Points at the same
     `tls-secret-${CAMUNDA_DOMAIN}` secret `create-certifcate.sh` already creates and nginx-ingress
     already serves — no separate CA secret needed; `keytool` will import a self-signed leaf as a
     trusted entry fine, it doesn't require a proper `CA:true` basicConstraint.
  2. **Reachability**: fixing #1 alone traded "unable to find valid certification path" for
     "signature check failed" — a *different* self-signed cert, same CN, was actually being
     served. Root cause: `BEHIND_REVERSE_PROXY=true` means `$CAMUNDA_DOMAIN` resolves via real
     public DNS to an external reverse proxy in front of this box, so a pod's own outbound call to
     `https://$CAMUNDA_DOMAIN/auth/...` leaves the cluster, goes out to the internet, and comes
     back in through that external hop — which terminates TLS with its own cert rather than
     passing through to this box's nginx-ingress. Confirmed with `openssl s_client` from inside a
     pod: connecting to `$CAMUNDA_DOMAIN:443` served a different fingerprint than
     `tls-secret-$CAMUNDA_DOMAIN`; connecting directly to the node's own internal IP with the same
     SNI served the expected one (nginx-ingress-microk8s-controller listens on hostPort 80/443, so
     it's reachable via the node's IP). Fixed in `2-install-camunda-microk8s.sh`, right after the
     TLS certs are created: when `BEHIND_REVERSE_PROXY=true`, it overrides MicroK8s's CoreDNS
     `Corefile` with a `hosts { <node-internal-ip> $CAMUNDA_DOMAIN }` block (`fallthrough` for
     everything else) so in-cluster pods resolve the domain to this node instead of round-tripping
     out — landing on this box's own nginx-ingress and the cert `global.tls.caBundle` trusts. Full
     Corefile replace, not a surgical patch of the live ConfigMap (see the comment in the script
     for why that's an acceptable tradeoff here). Applies regardless of `HELM_CHART_VERSION` -
     harmless on 8.9 since nothing there makes this outbound call, and pods reaching their own
     public domain without an unnecessary round trip through an external proxy is the correct
     topology either way, not an 8.10-only patch. Verified end-to-end on the live cluster: every
     pod in the `camunda` namespace reached `1/1 Running`, 0 restarts.

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
8. Apply Elasticsearch → PostgreSQL → keycunda, waiting on `rollout status`
   for each
9. `helm uninstall camunda || true`, delete the connectors PV/PVC, regenerate values, recreate
   host dirs + PVs, `helm install camunda camunda/camunda-platform --wait --timeout 20m`
10. `./seed-identity-mapping-rules.sh`

Connector secrets are no longer applied by the install script at all — that's now entirely a
post-install, admin-triggered action via Keycunda's Secrets Management page (see the "Secrets
Management" architecture note below and the README's "Secrets" section).

Re-running is the supported upgrade path: only the Helm release is torn down. The Elasticsearch,
PostgreSQL and Keycunda Deployment and their PVCs/state are `apply`-ed in place, so
**data survives**. The connectors PV/PVC is deliberately deleted and recreated each run.

## Conventions to follow

- **`microk8s kubectl`, never bare `kubectl`.** Scripts must work in the same shell that ran
  `1-install-microk8s.sh` (the bare `kubectl` alias only exists after a new login shell).
- **Run from the repo root.** All paths are relative (`./install-env.sh`, `./create-certifcate.sh`).
- `set -euo pipefail` at the top of every script; `|| true` on the calls that are allowed to fail.
- Loud banner-style progress output (`echo ====...`) between phases — match the existing style.
- Waits are explicit `kubectl rollout status`/`kubectl wait` with a timeout, never bare `sleep`.
- Version pins live as constants at the top of `configure-env.sh`
  (`ES_VERSION`, `PG_VERSION`, `KEYCUNDA_IMAGE`) and as `DEFAULT_*` prompts for the Camunda Helm chart
  and app version. `KEYCUNDA_IMAGE` is deliberately **not** prompted, unlike the Camunda-side versions
  — Keycunda is expected to need no end-user tuning once it's running, so bumping it
  is a code change (this constant), not something to surface in the wizard. README.md is
  user-facing and deliberately stays high-level (no versions table, no internals) — this file is
  where version pins and other implementation detail belong, not README.md.

## Architecture facts that are easy to get wrong

- **Two datastores, different jobs.** Orchestration uses the **RDBMS** secondary storage
  (`jdbc:postgresql://camunda-postgresql:5432/orchestration`, `exporters.rdbms.enabled: true`,
  `exporters.camunda.enabled: false`). Elasticsearch is there for **Optimize**. Don't assume the
  usual ES-backed Zeebe exporter setup.
- **One PostgreSQL, four databases**, all created by the init ConfigMap in
  `template-postgresql.yaml`: `keycunda` (user `keycunda`,
  matching the image name), `web-modeler` (user `webmodeler`), `orchestration` (user
  `orchestration`), and `identity` (user `identity` — Management Identity's own JPA store,
  needed only in GENERIC OIDC mode; see the note under `identity.externalDatabase` in
  `template-values-camunda.yaml`). Adding a database means editing that init script — it only
  runs on a **fresh** PG data volume, so an existing install needs manual SQL.
- **keycunda replaced Keycloak, and runs in the `camunda` namespace**
  alongside the rest of the platform, as a plain `kubectl apply`-ed Deployment/Service (not part
  of the Helm release — `helm uninstall camunda` never touches it). It was originally split into
  its own `jeltechnologies` namespace to model "external component this stack depends on", but
  that bought cross-namespace DNS footguns (a component reaching it internally has to remember the
  `.jeltechnologies` suffix everywhere) for no real benefit in a single-box demo, and it caused a
  real bug: Web Modeler's backend fetching JWKS over cluster DNS threw `UnknownHostException`.
  Co-locating it in `camunda` means the short Service name (`keycunda`)
  resolves correctly everywhere, it reads client secrets straight from `camunda-credentials`
  (including its own Postgres password, key `keycunda-postgresql-password`) instead of a
  duplicate `keycunda-credentials` Secret, and its Ingress reuses the same
  `tls-secret-${CAMUNDA_DOMAIN}` instead of a second copy of the cert.

  It runs as a single replica now (`template-keycunda.yaml`, `replicas: 1`)
  — the multi-replica setup was for testing future multi-node deployments, not a current need.
  The JDBC-backed session/token store described below still matters even at replicas: 1: it's what
  makes a pod restart (redeploy, node reschedule) not silently log everyone out, since sessions
  and tokens survive in Postgres rather than in-process memory.

  It has no volume of its own — all state is either in Postgres or in the
  `keycunda-signing-key` Secret, specifically so it can run multiple replicas and
  survive restarts without logging everyone out:
  - **Users** live in the `keycunda` Postgres database (`users` table).
    **Email is the unique login identifier; `name` is free text and not unique.** Users log in
    with email + password. The `preferred_username` claim carries the email (Camunda's own RBAC
    keys off this — see `orchestration.security.initialization` in
    `template-values-camunda.yaml`); a separate `name` claim carries the free-text display name,
    looked up fresh per token so admin-screen edits show up without a new login.
  - **HTTP sessions** and **issued tokens** (auth codes, access/refresh tokens) are also in that
    same database via `spring-session-jdbc` and `JdbcOAuth2AuthorizationService` — not the
    in-memory defaults — so a request doesn't need to land on the same pod that handled the
    previous one in the flow.
  - **The RSA JWT signing key** comes from the `keycunda-signing-key` Secret
    (`KEYCUNDA_JWT_SIGNING_KEY_PEM`), generated once by `2-install-camunda-microk8s.sh` (idempotent —
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
  `web-modeler`, `console`) is fixed in `keycunda/.../OidcClientsConfig.java`
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
  clients an admin creates/deletes at `/admin/clients` (`keycunda/.../client/`
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
  `orchestration`/`connectors` clients get from `keycunda.clients.orchestration.audience`.
  Also unlike user passwords, a client's plaintext secret **is** stored in the clear (the `secret`
  column, alongside `secret_hash`), populated on creation and on every "Generate new", and shown
  indefinitely on the "API clients" edit page (renamed from "Clients" — see `nav.html`) for as long
  as the client exists — there used to be a one-time-reveal design (an "I've copied this" button
  nulling the column via `ClientRepository.clearSecret`), but that traded away the ability to look
  a secret back up for a security benefit not worth the friction in a demo-grade app, so it was
  dropped in favor of always-visible. Regenerating still replaces both `secret` and `secret_hash`
  and invalidates the old value. The **add** page (`add-client.html`) has no separate "name" field
  at all — `AdminClientController.newClientForm` pre-fills a randomly generated Client ID
  (`client-XXXXXXXX`) into the one identifier field on page load, editable before submit, and the
  client's `name` column is always set equal to its `clientId` on creation (`AdminClientController
  .add`); the **edit** page still has a separate, independently-editable name field, which is a
  deliberate asymmetry — it's the field to use if you want a friendlier display name later without
  touching the immutable client ID. All known audiences are pre-checked by default on the add page
  (unchecking the ones a client doesn't need is one click; the old design pre-checked only
  Orchestration's).
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
  keycunda's own `/admin/clients` (see the `client` package and
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
- **The identity provider's brand is "Keycunda" (previously "Caddy" during an earlier iteration,
  before that a plain "Demo Identity Provider") — this time the underlying project was renamed to
  match, not just the UI text.** Directory (`keycunda/`), Maven artifact/groupId's trailing package
  segment (`com.jeltechnologies.keycunda`), Java class names (`KeycundaApplication`,
  `KeycundaProperties`, ...), Docker image name, Postgres database/user, Kubernetes
  Service/Deployment/ServiceAccount/Role/RoleBinding names, the signing-key Secret
  (`keycunda-signing-key`) and the `camunda-credentials` key `keycunda-postgresql-password` all
  say "keycunda" consistently now — see git history for the earlier Caddy-branding-only commit if
  you need to understand what changed between that pass and this one. **One thing deliberately did
  not change:** the OIDC endpoints still live at `/auth` — that's Camunda's own convention for
  this role, not Keycunda's brand name, so it stays regardless of what the app itself is called.
  `/auth` itself is meant to be login/OIDC only now, though — every human-facing admin page
  (portal, Users, Clients, Secrets) is reached through a friendly `/keycunda`/`/keycunda/users`/
  `/keycunda/clients`/`/keycunda/secrets` alias instead, each its own nginx Ingress object in
  `template-keycunda-ingress.yaml` (`rewrite-target` is per-Ingress-object, so one alias path needs
  one object) rewriting server-side to `/auth/admin...` before the request reaches the app — the
  app itself has no idea any of these aliases exist, it only ever sees `/auth/admin/...`.
  `admin/fragments/nav.html` links Users/Clients/Secrets via these plain `/keycunda/*` paths
  (not `th:href="@{...}"`, which would resolve against the app's own `/auth` context-path and
  defeat the point). This only covers entry-point navigation, not every internal hop: the
  add/edit/delete forms and controller redirects on those pages still round-trip through
  `/auth/admin/...` under the hood (same as Secrets already did before Users/Clients got the same
  treatment), so the address bar does flip to `/auth/...` once you submit a form rather than just
  click a nav link.
  The portal is organized into two areas: **Identity Management** (Users, Clients) and **Secrets
  Management** (the connectors Kubernetes Secret, described next) — deliberately not framed as an
  open-ended "Cluster Management" category anymore, since the portal shouldn't imply more
  functions are coming before they actually exist.
- **Secrets Management (`secret/`, `web/AdminSecretController.java`, `secrets.html`) treats
  Kubernetes itself as the source of truth for reads - there is no database table for it, and no
  HTTP session cache either.** The list page fetches the live connectors Secret fresh via
  `ClusterSecretsApplier.fetch` on every single load, full stop - reloading the page is the only way
  to be sure you're looking at what's actually in the cluster. An earlier version staged edits in a
  `@SessionScope` bean (`SecretsWorkingCopy`, since deleted) seeded once per browser session and
  only pushed back on an explicit "Apply to cluster" click. That cache went stale in a way that cost
  real debugging time: a Keycunda upgrade changed which Secret name is managed (see
  `SecretYamlCodec.DEFAULT_SECRET_NAME` below), and an admin's already-open browser session kept
  showing the old Secret's contents indefinitely, because nothing ever forced a re-fetch once a
  session had loaded once.

  A second version of this feature (briefly) went to the opposite extreme - every add/edit/delete/
  import applied to Kubernetes and restarted the connectors pod immediately, with no staging at
  all - correct, but a poor fit for actually editing several secrets in a row (a restart-and-wait
  per keystroke). **The current design keeps reads always-live but moves staging out of the server
  entirely, into the browser**: `secrets.html` bootstraps a plain JS object from the page's initial
  live snapshot (via Thymeleaf JavaScript inlining - `th:inline="javascript"`, `secretsMap` model
  attribute), and add/edit/delete/import all mutate that in-memory JS object and re-render the
  table client-side - no server round trip, no restart, nothing written anywhere. "Delete all"
  clears the whole in-browser object at once (after a confirm dialog) - the fast path for starting
  over from a clean slate, e.g. immediately followed by importing a fresh file. Import
  (`POST /admin/secrets/import` / `import-text`) is now a parse-only JSON API: it decodes the
  uploaded/pasted YAML or .env content and hands the entries straight back to the browser to merge,
  it never touches Kubernetes. The **only** endpoint that writes anything is
  `POST /admin/secrets/apply-to-cluster`, which takes the browser's *entire* current working set as
  a JSON body (not an incremental diff) and submits it wholesale through the same background-job/
  status-page mechanism described below. Reloading the list page afterward re-fetches live from
  Kubernetes again, so staleness can never survive longer than the current, unsaved browser tab -
  closing the tab without clicking "Apply to cluster" simply discards the edits, same as closing a
  spreadsheet without saving. Values are **not** encrypted anywhere - they sit in the cluster
  exactly as any `kubectl create secret` would leave them (base64 in etcd, no extra layer). This was
  a deliberate choice, not an oversight: an earlier version of this feature stored secrets encrypted
  (AES-GCM) in this app's own Postgres database with Kubernetes as a derived export target, but
  that's needless complexity for what CLAUDE.md already frames as a demo/learning tool, not an
  enterprise secrets manager. The `.env` import parser (`SecretEnvCodec`) is deliberately non-standard: any line that
  isn't itself a new `KEY=` assignment is treated as a continuation of the previous key's value
  (newline preserved), so a multi-line, unquoted value - e.g. a GCP service-account JSON key pasted
  straight after `KEY=` - round-trips correctly. This is not strict dotenv syntax and should not be
  "fixed" into it. Import always upserts by key into the browser's current working set; nothing
  already present is ever removed by an import just because it's absent from the imported file.
  **The managed Secret's name (`SecretYamlCodec.DEFAULT_SECRET_NAME`) is `"connector-secrets"`, not
  `"camunda-connector-secrets"`** - found the hard way after an install where the old
  `update-connector-secrets.sh` script (see below) had already wired the connectors Deployment's
  `envFrom` to a Secret named `connector-secrets` on a previous run, but a since-fixed version of
  this constant defaulted to `camunda-connector-secrets` instead, so the admin UI silently created
  and edited a *different*, disconnected Secret that the connectors pod never read from - values
  added through the page never reached the pod, with no error anywhere to suggest why. Whatever
  this constant is set to must match the name the real Deployment's `envFrom` needs; if you ever
  need to confirm which Secret name is actually live, `kubectl get deployment camunda-connectors -n
  camunda -o jsonpath='{.spec.template.spec.containers[0].envFrom}'` is the ground truth, not
  a recollection of which script or UI was used last.
- **Every secret key added through the Secrets page gets a `SECRET_` prefix automatically if it
  doesn't already have one - not a Keycunda convention, a Camunda requirement.** Camunda's
  self-managed connector-runtime (`EnvironmentSecretProvider`) only resolves an environment-
  variable-backed secret whose name carries this exact prefix - it's a security hardening added in
  8.9 so an arbitrary container env var (`PATH`, `JAVA_HOME`, ...) can't accidentally be exposed as
  a connector secret. Found the hard way: a connector kept reporting `Secret with name
  'INBOUND_MAIL_IMAP_PORT' is not available` even though that exact env var, with the right value,
  was confirmed present in the connectors container - the connector logs themselves eventually
  showed `EnvironmentSecretProvider` explicitly rejecting it and naming the fix ("Rename it to
  'SECRET_INBOUND_MAIL_IMAP_PORT'"). Camunda SaaS has no equivalent requirement, since SaaS never
  exposes secrets as raw container env vars at all - they go through Console's own managed Secrets
  store instead, a different backend entirely. `secrets.html`'s `normalizeKey()` (uppercases the
  typed key - Kubernetes/shell convention - then applies `ensurePrefixed()`/`KEY_PREFIX`, mirrored
  server-side as `AdminSecretController.SELF_MANAGED_SECRET_PREFIX`, used only for the SaaS export
  below) runs on add, edit (renaming a key), and import (file or pasted), so an admin never has to
  know/remember either convention. Whenever the prefix specifically had to be added (not just the
  uppercasing), `notePrefixAdded()` shows an info modal explaining why - deliberately **every**
  time this happens, not once-per-session; that repetition is an intentional design choice, not
  an oversight to dedupe. The BPMN's own `{{secrets.NAME}}` references never need to change - the
  prefix lives purely in the underlying env var/Kubernetes Secret key, not in what the process
  asks for by name.

  **The Export panel's "Camunda Self Managed"/"Camunda Software as a Service (Saas)" target
  selector exists because of this same asymmetry.** Self Managed exports keys as-is
  (`SECRET_`-prefixed, ready to feed back into this same stack); SaaS strips that prefix back off
  (`AdminSecretController.stripPrefixForSaas`) before generating the `.yaml`/`.env`, since pasting
  a `SECRET_`-prefixed name into SaaS Console's Secrets screen would just create a secret Console
  itself never needed prefixed - the process's `{{secrets.NAME}}` reference is what actually has to
  match Console's secret name there. One shared radio group (not a `<select>` per format) steers
  both download buttons via `th:formaction`-style JS wiring (`exportSecrets` reads whichever radio
  is checked), rather than duplicating the choice per button.

  **Export is POSTed the browser's current in-memory working set, not a fresh Kubernetes fetch -**
  `exportYaml`/`exportEnv` take `@RequestBody Map<String, String>` instead of calling
  `ClusterSecretsApplier.fetch`, so a download reflects exactly what's shown on screen, including
  unapplied edits, the same "what you see is what you get" principle "Apply to cluster" already
  followed. Since a JS `fetch()` POST can't trigger a native browser download the way a `<form
  method="get">` navigation could, `secrets.html`'s `downloadTextAsFile` builds a `Blob` from the
  response text and clicks a throwaway `<a download>` element instead - the actual YAML/`.env`
  serialization still happens server-side, reusing `SecretYamlCodec`'s SnakeYAML-based escaping
  (correct for values with quotes/colons/newlines) rather than a hand-rolled JS equivalent that
  would risk getting that escaping subtly wrong.
- **"Apply to cluster" (`secret/ClusterSecretsApplier.java`) talks to the Kubernetes API directly
  via the fabric8 `kubernetes-client` Java library, not by shelling out to a script from inside the
  pod.** An earlier iteration of this feature was a plain bash script
  (`update-connector-secrets.sh`, since removed - it needed `microk8s kubectl` and a host shell
  context, neither of which exist inside the Keycunda container, so reusing it as-is was never
  actually possible) run manually as an install step; `ClusterSecretsApplier` is a from-scratch
  reimplementation of the same delete/create-Secret, patch-Deployment-envFrom, restart,
  wait-for-rollout steps, triggered from the admin UI instead of a separate manual script run.
  **`applySecret` explicitly deletes the Secret before creating it fresh, rather than an
  upsert-style `createOrReplace`** - this was the documented intent from the start (see "same
  delete/create-Secret ... steps" just above) but the code actually called `createOrReplace` until
  this was tightened up, leaving an unverified assumption that a replace would implicitly drop any
  key no longer in the submitted set. An explicit delete removes any doubt: whatever's submitted -
  including an empty set, via "Delete all" then "Apply to cluster" - is exactly what the Secret
  contains afterward, never a leftover key from a prior apply or from something created outside
  this app entirely (e.g. a stray manual `kubectl create secret`). Deleting a Secret that doesn't
  exist yet is a harmless no-op. After writing the Secret, `apply()` re-fetches it and compares against what was submitted before
  doing anything else, throwing if they don't match - the explicit "verify it actually loaded"
  step this feature was built around, not just a write-and-hope. Needs its own RBAC: a
  `keycunda` ServiceAccount (referenced via `serviceAccountName` in
  `template-keycunda.yaml`) bound to a Role granting
  get/list/create/update/delete on `secrets` and get/list/patch on `deployments` - no `pods`
  access needed at all (see the rollout-restart fix just below for why) - all namespaced to
  `camunda`, defined in `template-keycunda-rbac.yaml` and applied by
  `2-install-camunda-microk8s.sh` before the Keycunda Deployment itself (the Deployment
  references the ServiceAccount by name, so it must already exist). Unlike the old script's
  unconditional JSON-patch append (which would have added a duplicate `envFrom` entry on every
  re-run), `ClusterSecretsApplier` checks whether the Deployment's first container already
  references the target Secret name before patching, so repeated applies stay idempotent. The
  connectors Deployment is found by a case-insensitive name match containing "connector", not a
  hardcoded name.

  **The restart itself is a `kubectl rollout restart`-equivalent (fabric8's
  `.rolling().restart()`, patching `spec.template.metadata.annotations["kubectl.kubernetes.io/
  restartedAt"]`) - not a direct pod delete, which is what this originally did and which had a
  real, reported bug in it.** Deleting the connectors pod directly never touches the Deployment's
  `spec.template` at all, so `metadata.generation` never changes either - from the Deployment
  controller's point of view, nothing "rolled out", it just reactively recreated a pod the
  ReplicaSet noticed was missing. That mattered because the post-apply wait
  (`ClusterSecretsApplier.isFullyRolledOut`) needs something reliable to wait *for*: fabric8's own
  built-in `waitUntilReady()`/`isDeploymentReady()` only compares desired vs. available replica
  counts, and never looks at `status.observedGeneration` - so right after deleting the pod, the
  Deployment's status could still transiently report the *old* (already-ready) counts for a
  moment, and the wait would return almost instantly, before the new pod had actually started.
  Symptom actually hit in practice: an admin added a secret value, "Apply to cluster" reported
  success suspiciously fast, and the connector still couldn't see the new value - the pod was
  never really replaced in time. Fixed by (a) triggering a real rollout-restart, which does bump
  `metadata.generation`, and (b) replacing the wait with a hand-rolled poll
  (`ClusterSecretsApplier.isFullyRolledOut`, unit-tested directly since the in-JVM CRUD mock
  server used by `ClusterSecretsApplierTest` has no real controller to advance
  `status.observedGeneration` on its own) that requires `status.observedGeneration` to have
  caught up to the generation produced by *this specific* restart, in addition to the replica
  counts fabric8's own check already looks at.

  **This imperative `envFrom` patch is not by itself durable across a reinstall, and that gap was
  a second real bug behind connector secrets silently not reaching the pod.** `2-install-camunda-
  microk8s.sh`'s supported reinstall path is `helm uninstall camunda` followed by a fresh
  `helm install` on every run (see "Install flow" above) - that recreates the connectors Deployment
  from the Helm chart's rendered values with no memory of any prior `kubectl`-level patch, so the
  `envFrom` reference `ClusterSecretsApplier` had added disappeared on every reinstall, silently,
  with no error anywhere. Fixed by also declaring the same reference directly in
  `template-values-camunda.yaml`'s `connectors.envFrom` (the chart supports it natively), marked
  `optional: true` so a fresh install - before this Secret has ever been created via the Secrets
  Management page - doesn't leave the connectors pod stuck in `ContainerCreating`. Both halves
  matter for different reasons: the Helm value makes the reference survive every reinstall from the
  start, while `ClusterSecretsApplier.ensureEnvFromReference`'s imperative check-and-patch stays in
  place as a defensive idempotent no-op (useful for an environment mid-upgrade that hasn't re-run
  the full install script yet). If you ever suspect this is broken again, the ground truth is
  `kubectl get deployment camunda-connectors -n camunda -o jsonpath='{.spec.template.spec.containers[0].envFrom}'`
  and `kubectl exec deploy/camunda-connectors -n camunda -- env` - not a recollection of which
  secret name was used last.

  The apply itself runs on a background thread from `AdminSecretController` (a dedicated
  single-thread `ExecutorService`, so two "Apply to cluster" clicks submitted close together can't
  race each other) with progress tracked in `secret/ApplyJobStatus.java` - a single in-memory
  mutable slot, not persisted, which is fine only because the pod runs at `replicas: 1`; a pod
  restart mid-apply is meant to read as "nothing running" rather than resurrect a stale job.
  **`secrets.html` shows this progress (and its final result) in an in-page themed modal now**,
  polling `GET /admin/secrets/apply-status.json` (a plain `{state, message}` JSON twin of
  `ApplyJobStatus.Job`) every 1.5s via `fetch()`, rather than navigating to a separate page - a
  nicer UX than the browser's own dialogs, and consistent with the same modal now also replacing
  `window.confirm()` for "Delete all" and per-row "Delete" (`showConfirmModal`/`showWaitModal`/
  `showResultModal` in `secrets.html`'s script). The modal's "Close" button reloads the page rather
  than just hiding itself, specifically so the table re-fetches live from Kubernetes and reflects
  what was actually just applied. `apply-status.html`/`GET /admin/secrets/apply-status` (the
  original full-page version, still polling via a plain `<meta http-equiv="refresh">` with no
  JavaScript) is kept as-is and still reachable directly - a working fallback for a client with
  JavaScript disabled, and one less thing to migrate. `secrets.html` itself is the one deliberate
  exception to this app's zero-JS convention, since client-side staging needs it. This used to
  also be where a resync-the-session-working-copy step lived, with a real constraint around it (the
  background thread has no HTTP session bound to it, so touching a `@SessionScope` bean from there
  threw `IllegalStateException: Scope 'session' is not active for the current thread`) - that whole
  concern disappeared along with the working copy itself, since there's nothing left to resync.
- **Ingress-behind-proxy pitfalls.** Everything is path-routed on one host, TLS-terminated at
  nginx. Components must therefore be told their context path *and* to trust `X-Forwarded-*`.
  The Web Modeler `forward-headers-strategy: native` block in `template-values-camunda.yaml`
  documents one such fix (Tomcat + `https-only: true` → redirect loop); expect similar issues
  whenever a component is upgraded and read that comment before touching it.
  `keycunda` sets the same `server.forward-headers-strategy=native` for the
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

For `keycunda/`, there **is** a real build to verify:

```bash
cd keycunda && mvn -q verify         # compiles + runs the test suite
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
`orchestration-postgresql-password`, `keycunda-postgresql-password`) are all set to the
same `$PASSWORD` too, doing double duty as both database passwords and OAuth2 client secrets for
`keycunda`. The
**`keycunda-signing-key` Secret is deliberately separate** and handled differently: unlike
`camunda-credentials` (deleted and recreated on every install run, harmless since the value always
comes from the same `$PASSWORD`), this one holds randomly generated key material with no external
source of truth, so `2-install-camunda-microk8s.sh` only creates it if it doesn't already exist.
Never change that to an unconditional delete-and-recreate — it would silently invalidate every
outstanding token on the next install run.

**`.env` / `.env.example` convention.** Every place secrets live has a matching `.example` file
committed alongside it, documenting the shape with placeholder values only:
- `.env.example` (root) mirrors `install-env.sh` — reference only; `configure-env.sh` is still
  the actual way to produce `install-env.sh`, nobody should hand-write it from the example.
- `keycunda/.env.example` mirrors the `.env` a developer creates for local
  `mvn spring-boot:run` (see that subproject's README).

Both real `.env` files are gitignored. When adding a new secret-bearing variable anywhere, add it
to the relevant `.example` file in the same change — an `.example` file that's drifted out of sync
with what the scripts/app actually read is worse than no example at all.
