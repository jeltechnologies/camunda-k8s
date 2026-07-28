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
The identity provider is explicitly **demo-grade**, not an enterprise-security IdP: no MFA, no
audit logging, no key rotation, single in-memory JWT signing key regenerated on every restart. Do
not present it as a production-hardened component.

## Repository layout

| File | Role |
|---|---|
| `1-install-microk8s.sh` | Host prep: apt upgrade, swapoff, MicroK8s 1.32, addons, kubectl config, Helm v4. Run once, then reboot. |
| `2-install-camunda-microk8s.sh` | The main orchestrator. Everything else is called from here. |
| `configure-env.sh` | Interactive wizard; **writes `install-env.sh`** (the generated config). |
| `install-fix-hosts.sh` | Adds `/etc/hosts` entries (skipped when behind a reverse proxy). Needs root. |
| `create-certifcate.sh` | Self-signed TLS cert → `tls-secret-<domain>` k8s secret. (Filename typo is intentional//historic — do not "fix" it without updating callers.) |
| `template-*.yaml` | `envsubst` input templates — **the only YAML you should edit**. |
| `camunda-demo-identity-provider/` | Spring Boot OIDC identity provider — source, Maven build, own README. |
| `.github/workflows/build-camunda-demo-identity-provider.yml` | Builds/pushes its image to GHCR on push to `main` or an `idp-v*` tag. |
| `update-connector-secrets.sh` | Applies optional `connector-secrets.yaml`, patches the connectors Deployment with `envFrom`, restarts the pod. |
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
- camunda-demo-identity-provider: `${IDP_IMAGE} ${CAMUNDA_DOMAIN} ${PASSWORD} ${DEMO_USERNAME} ${DEMO_EMAIL}`
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
  (`ES_VERSION`, `PG_VERSION`) and as `DEFAULT_*` prompts for the Camunda Helm chart, app version,
  and the identity-provider image (`IDP_IMAGE`). If you bump any of these, **update the Versions
  table in `README.md` to match** — it is maintained by hand.

## Architecture facts that are easy to get wrong

- **Two datastores, different jobs.** Orchestration uses the **RDBMS** secondary storage
  (`jdbc:postgresql://camunda-postgresql:5432/orchestration`, `exporters.rdbms.enabled: true`,
  `exporters.camunda.enabled: false`). Elasticsearch is there for **Optimize**. Don't assume the
  usual ES-backed Zeebe exporter setup.
- **One PostgreSQL, three databases**, all created by the init ConfigMap in
  `template-postgresql.yaml`: `idp` (user `idp`), `web-modeler` (user `webmodeler`),
  `orchestration` (user `orchestration`). Adding a database means editing that init script — it
  only runs on a **fresh** PG data volume, so an existing install needs manual SQL.
- **camunda-demo-identity-provider replaced Keycloak.** It's a stateless Spring Boot Deployment
  (`template-camunda-demo-identity-provider.yaml`) — no volume of its own. The `users` table lives
  in the `idp` Postgres database; the app itself holds no state, so restarting the pod is harmless
  *except* that it regenerates its RSA JWT signing key and clears in-memory HTTP sessions on every
  restart, invalidating outstanding tokens and logging everyone out. Camunda is configured with
  `global.identity.auth.type: "GENERIC"` (see the comment block at the top of that section in
  `template-values-camunda.yaml`) — a documented, supported Camunda 8.8+ mode for connecting to any
  standards-compliant external OIDC provider instead of the chart's bundled Keycloak. Camunda's own
  `identity` component still manages authorization/roles in its own DB; only *authentication*
  moved. The OAuth2 client set (`camunda-identity`, `orchestration`, `optimize`, `web-modeler`,
  `console`) is fixed in `camunda-demo-identity-provider/.../OidcClientsConfig.java` — adding a new
  Camunda component means a code change there **and** a matching block in
  `template-values-camunda.yaml`, cross-checked against `helm show values camunda/camunda-platform
  --version <HELM_CHART_VERSION>` since field names have moved between chart versions (e.g.
  `orchestration.security.authentication.oidc.issuer`, not `issuerUrl`).
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
`orchestration-postgresql-password`) are all set to the same `$PASSWORD` too, doing double duty as
both database passwords and OAuth2 client secrets for `camunda-demo-identity-provider`.
