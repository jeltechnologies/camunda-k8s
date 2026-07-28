# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Bash + Kubernetes manifests that install a complete Camunda 8 platform (Orchestration/Zeebe,
Operate, Tasklist, Web Modeler, Optimize, Console, Identity, Connectors) onto a **single Ubuntu
box** running MicroK8s, fronted by nginx ingress with Keycloak OIDC on real path-based URLs.

There is **no application code, no build, no test suite, and no CI**. Everything here is shell
scripts and YAML templates. Target audience is demos, learning and experimenting — explicitly
not production (see README).

## Repository layout

| File | Role |
|---|---|
| `1-install-microk8s.sh` | Host prep: apt upgrade, swapoff, MicroK8s 1.32, addons, kubectl config, Helm v4. Run once, then reboot. |
| `2-install-camunda-microk8s.sh` | The main orchestrator. Everything else is called from here. |
| `configure-env.sh` | Interactive wizard; **writes `install-env.sh`** (the generated config). |
| `install-fix-hosts.sh` | Adds `/etc/hosts` entries (skipped when behind a reverse proxy). Needs root. |
| `create-certifcate.sh` | Self-signed TLS cert → `tls-secret-<domain>` k8s secret. (Filename typo is intentional//historic — do not "fix" it without updating callers.) |
| `template-*.yaml` | `envsubst` input templates — **the only YAML you should edit**. |
| `update-connector-secrets.sh` | Applies optional `connector-secrets.yaml`, patches the connectors Deployment with `envFrom`, restarts the pod. |
| `tail-connector-logs.sh` | Convenience log tail for the connectors pod. |

## The templating model — most important thing to know

Generated files are **gitignored and overwritten on every install**. Never edit them:

```
template-values-camunda.yaml  --envsubst-->  values-camunda.yaml     (gitignored)
template-elasticsearch.yaml   --envsubst-->  piped straight to kubectl apply
template-postgresql.yaml      --envsubst-->  piped straight to kubectl apply
template-keycloak.yaml        --envsubst-->  piped straight to kubectl apply
template-keycloak-ingress.yaml
template-volumes.yaml
configure-env.sh              --writes-->    install-env.sh          (gitignored)
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
- keycloak: `${KEYCLOAK_VERSION} ${CAMUNDA_DOMAIN} ${PASSWORD}`
- keycloak-ingress: `${CAMUNDA_DOMAIN}`
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
7. Create the `camunda-credentials` secret imperatively — **all seven keys get the same
   `$PASSWORD`**. Every template refers to it via `existingSecret`/`existingSecretKey`; no
   password is ever templated into the Helm values.
8. Apply Elasticsearch → PostgreSQL → Keycloak StatefulSets, waiting on `rollout status` for each
9. `helm uninstall camunda || true`, delete the connectors PV/PVC, regenerate values, recreate
   host dirs + PVs, `helm install camunda camunda/camunda-platform --wait --timeout 20m`
10. `./update-connector-secrets.sh`

Re-running is the supported upgrade path: only the Helm release is torn down. The Elasticsearch,
PostgreSQL and Keycloak StatefulSets and their PVCs are `apply`-ed in place, so **data survives**.
The connectors PV/PVC is deliberately deleted and recreated each run.

## Conventions to follow

- **`microk8s kubectl`, never bare `kubectl`.** Scripts must work in the same shell that ran
  `1-install-microk8s.sh` (the bare `kubectl` alias only exists after a new login shell).
- **Run from the repo root.** All paths are relative (`./install-env.sh`, `./create-certifcate.sh`).
- `set -euo pipefail` at the top of every script; `|| true` on the calls that are allowed to fail.
- Loud banner-style progress output (`echo ====...`) between phases — match the existing style.
- Waits are explicit `kubectl rollout status`/`kubectl wait` with a timeout, never bare `sleep`.
- Version pins live as constants at the top of `configure-env.sh`
  (`ES_VERSION`, `KEYCLOAK_VERSION`, `PG_VERSION`) and as `DEFAULT_*` prompts for the Camunda
  Helm chart and app versions. If you bump any of these, **update the Versions table in
  `README.md` to match** — it is maintained by hand.

## Architecture facts that are easy to get wrong

- **Two datastores, different jobs.** Orchestration uses the **RDBMS** secondary storage
  (`jdbc:postgresql://camunda-postgresql:5432/orchestration`, `exporters.rdbms.enabled: true`,
  `exporters.camunda.enabled: false`). Elasticsearch is there for **Optimize**. Don't assume the
  usual ES-backed Zeebe exporter setup.
- **One PostgreSQL, three databases**, all created by the init ConfigMap in
  `template-postgresql.yaml`: `keycloak` (user `camunda`), `web-modeler` (user `webmodeler`),
  `orchestration` (user `orchestration`). Adding a database means editing that init script — it
  only runs on a **fresh** PG data volume, so an existing install needs manual SQL.
- **Keycloak is self-managed**, not the Helm chart's bundled one. It lives at `/auth`
  (`KC_HTTP_RELATIVE_PATH`), is reached in-cluster over plain HTTP at `camunda-keycloak:80`, and
  publicly over HTTPS. In the Helm values this shows up as the `publicIssuerUrl` (https, external)
  vs `issuerBackendUrl`/`tokenUrl`/`jwksUrl` (http, in-cluster) split. Keep that split intact —
  collapsing it to one URL breaks either token validation or the browser redirect.
- **Ingress-behind-proxy pitfalls.** Everything is path-routed on one host, TLS-terminated at
  nginx. Components must therefore be told their context path *and* to trust `X-Forwarded-*`.
  The Web Modeler `forward-headers-strategy: native` block in `template-values-camunda.yaml`
  documents one such fix (Tomcat + `https-only: true` → redirect loop); expect similar issues
  whenever a component is upgraded and read that comment before touching it.
- **Host-path volumes.** `~/camunda-docs` (document store, mounted at `/camunda-docs` in
  identity/optimize/connectors/orchestration) and `~/camunda-connectors` (custom connector JARs,
  mounted at `/opt/custom` with `LOADER_PATH=/opt/custom/connectors`). Both are `hostPath` PVs
  templated with `${HOME}`, so they are tied to the installing user's home directory.
- **`SWAGGER_ENABLED` defaults to `false` on purpose** — it exposes the full REST API surface
  unauthenticated. Do not flip the default; it is called out as a security note in the README.

## Verifying changes

No test harness exists. Reasonable checks before declaring a change good:

```bash
bash -n <script>.sh                                       # syntax
shellcheck <script>.sh                                    # if available
source ./install-env.sh && envsubst '${VAR} ...' < template-x.yaml   # inspect rendered YAML
microk8s kubectl apply --dry-run=server -f -               # validate against the API server
microk8s kubectl get pods -n camunda -w
./tail-connector-logs.sh
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
unasked.
