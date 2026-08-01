# Camunda 8 Self Contained Installation

A fully automated installation of Camunda 8 on a single Ubuntu box  for experimenting, learning and demos.

**This cannot be used in production**, because this approach lacks the robustness, scale, high availbility and license needed in enterprise environments.

## Why this exists

This project takes a different approach:

- **If I could turn back time** - Dare to make mistakes, or restore the complete system to "before the demo". This is achieved by deploying on Proxmox or VirtualBox virtual machines and using their snapshots and backup functions.
- **Full platform** — Includes Web Modeler and Optimize, which are often left out of local setups due to complexity.
- **Real URLs** — every component is accessible at a proper path on your domain, the same way a production deployment works. Other local deployments use Docker Compose or NPM with port forwarding — each component on a different port, no shared authentication, and no ingress. 
- **Re-runnable** — running the install script again upgrades the existing installation, preserving your data
- **Two script only** — run two scripts and one restart, taking max 30 minutes.

## What you get

A complete Camunda 8 platform running on a single machine:

| Component | URL |
|---|---|
| Web Modeler | `https://<your-domain>/modeler` |
| Operate / Tasklist | `https://<your-domain>/orchestration` |
| Optimize | `https://<your-domain>/optimize` |
| Console | `https://<your-domain>/console` |
| Identity | `https://<your-domain>/identity` |
| Keycunda (login) | `https://<your-domain>/auth` |
| Zeebe gRPC | `grpc://zeebe.<your-domain>:26500` |
| Swagger UI | `https://<your-domain>/orchestration/swagger` *(disabled by default)* |

All components are secured with OIDC authentication, provided by a minimal purpose-built
identity provider called **Keycunda** (`keycunda/`, a small Spring Boot app — see its own
[README](keycunda/README.md)) rather than a full Keycloak instance. Keycloak is a solid choice for
production, but its realms, clients and role screens are a lot to navigate for a demo box where
all you want is "add a user" or "add an integration". Keycunda still serves its OIDC endpoints at
`/auth` (Camunda's own convention for this role, kept as-is), and trades Keycloak's generality for
a small admin portal at `/auth/admin`, organized into two areas:

- **Identity Management**
  - **Users** — human accounts: email, password, an admin flag.
  - **Clients** — OAuth2 client-credentials applications, the same concept Camunda's own Console
    renamed from "M2M" to "Clients" in 8.9 (default from 8.10 on) — support Keycunda already has,
    ahead of that switch. Creating one generates its ID and secret for you, lets you pick which
    Camunda API(s) it's authorized for from checkboxes instead of typing exact audience strings,
    and — since this is a demo, not production — keeps the secret visible afterward rather than a
    one-time reveal.
- **Secrets Management** — see the "Secrets" section below.

As the name says, this is a **demo-grade** OIDC provider — it covers what this platform needs to
authenticate and issue tokens, not the security hardening, auditing, or compliance controls of an
enterprise Identity Provider.

## Requirements

This scripts have been tested in Proxmox virtual machines. I use very modest hardware, Intel i3 8100T. You will need to provision the following in Proxmox:
- Ubuntu 26.04 Server or Desktop
- Minimal 8 GB memory (16 GB recommended)
- Minimum 4 vCPU.
- 32 GB disk (64 GB recommemded)

### Network access to the web applications
- The easiest way to reach the web application is by installing Camunda on Ubuntu Desktop. You can then use the web browser inside the virtual machine, or use Remote Desktop Connection. To reach the server from other machines in your network, you must change the hosts files of these machines. You will get warnings on self signed certificates, which is normal.
- For exposure to the internet, you should use a reverse proxy. This is provided by solutions like Pangolin or Cloudflare. You will then also get a real certifcate. Make sure to not use a secure password during installation. 

> **Reverse proxy tip:** During `./2-install-camunda-microk8s.sh` you will be asked whether the deployment is behind a reverse proxy (Cloudflare, Pangolin, etc.). Answer `true` if the public domain resolves externally. The installer will then skip adding the domain to `/etc/hosts`, preventing traffic from looping back instead of going through the proxy.

> **Swagger UI:** Disabled by default (`SWAGGER_ENABLED=false`). Only enable it for local/dev setups — the Swagger UI exposes the full REST API surface without additional authentication and should never be turned on when the instance is reachable from the public internet.

## Installation

```bash
# Step 1: install Kubernetes
./1-install-microk8s.sh
sudo reboot

# Step 2: install Camunda
./2-install-camunda-microk8s.sh
```

The second script will prompt for your Helm chart version. Choose one of the published versions from 
[Camunda 8 Helm Chart Version Matrix](https://helm.camunda.io/camunda-platform/version-matrix/)

After this the script prompts for domain, password, and optional Ollama/GitLab settings, then install everything automatically. Expect 15–20 minutes on first run.

## Architecture

All components run as Kubernetes workloads inside MicroK8s. External dependencies (Keycunda,
PostgreSQL, Elasticsearch) are deployed before the Camunda Helm chart is installed. The nginx
ingress controller handles TLS termination and routes all traffic by path prefix — no NodePorts
or port forwarding anywhere.

Keycunda runs in the `camunda` namespace alongside the rest of the platform, as a plain
`kubectl apply`-ed Deployment/Service — it's not part of the Helm release, since it's an external
component this stack depends on, not part of the Camunda platform itself. It gets its own Ingress
object (routed by nginx on the `/auth` path prefix, same domain, same TLS cert as everything else).

```
Internet → nginx ingress (443)
             ├── /auth          → Keycunda (login, OIDC)
             ├── /modeler       → Web Modeler
             ├── /orchestration → Zeebe / Operate / Tasklist
             ├── /optimize      → Optimize
             ├── /identity      → Identity (authorization/roles, unrelated to login)
             └── /console       → Console
```

Camunda's own `identity` component still manages authorization (roles/permissions in its own
database) — only *authentication* moved from Keycloak to Keycunda. Its container image is built
by `.github/workflows/build-keycunda.yml` and published to GHCR; the install script pulls it by
tag (`KEYCUNDA_IMAGE`, a constant in `configure-env.sh`).

## Custom connectors

Drop connector JARs into `~/camunda-connectors` on the host. 

## Secrets

Connector credentials (API keys, connection strings, service-account JSON keys, ...) are managed
entirely through **Keycunda's Secrets Management page** (`/auth/admin/secrets`, admin users only).
Kubernetes is the source of truth here, not a database: the first time you open the page in a
browser session, it fetches the live connectors Secret and holds a working copy in your session
while you edit it. Add/edit/delete/import only change that in-memory working copy — nothing in the
cluster changes until you click **Apply to cluster**, since the connectors pod only ever picks up a
change after a restart anyway, so pushing every micro-edit through immediately would accomplish
nothing except extra API calls. Values are masked with a click-to-view toggle, sorted by key, with
unique-key enforcement.

- **Add / edit / delete** secrets one at a time from the list, or **paste** a Kubernetes `kind:
  Secret` manifest or `.env` content directly into a textarea — the page auto-detects which format
  you pasted. Either way, pasted/imported keys already present in the working copy are updated in
  place; nothing already staged is removed just because it's absent from what you pasted.
- **Import** a `.yaml`/`.yml` manifest or a `.env` file instead, if you'd rather upload one. The
  `.env` import intentionally accepts multi-line, unquoted values — e.g. a service-account JSON key
  pasted directly after `KEY=` — not just strict single-line `KEY=VALUE` entries.
- **Export** the working copy as a Kubernetes `kind: Secret` manifest or as a `.env` file, e.g. to
  keep an offline backup or diff against a previous version.
- **Apply to cluster** — writes the working copy to the named Kubernetes Secret, re-fetches it to
  verify the values actually landed, wires it into the connectors Deployment's `envFrom` if it
  isn't already, and restarts the connectors pod so it picks up the new values. Requires the RBAC
  granted in `template-keycunda-rbac.yaml` (applied automatically by
  `2-install-camunda-microk8s.sh`). A "please wait" overlay stays up until the restart finishes.
- Values are **not** encrypted by this app — they sit in the cluster exactly as any
  `kubectl create secret` would leave them (base64 in etcd, no additional encryption layer). This
  is a demo/learning tool, not an enterprise secrets manager; if that's not enough protection for
  your use case, that's a signal this isn't the right tool for it.

## Files

| File | Purpose |
|---|---|
| `1-install-microk8s.sh` | Installs MicroK8s and Helm v4 |
| `2-install-camunda-microk8s.sh` | Installs Camunda and all dependencies |
| `configure-env.sh` | Interactive configuration wizard |
| `.env.example` | Reference for what secrets/config this repo needs — copy nothing, just run `configure-env.sh` |
| `template-values-camunda.yaml` | Helm values template |
| `keycunda/` | Source + Maven build for Keycunda, the custom OIDC identity provider |
| `template-keycunda.yaml` | Keycunda Deployment and Service |
| `template-keycunda-ingress.yaml` | Keycunda ingress |
| `template-keycunda-rbac.yaml` | ServiceAccount/Role/RoleBinding for Keycunda's Secrets Management "Apply to cluster" function |
| `template-postgresql.yaml` | PostgreSQL StatefulSet |
| `template-elasticsearch.yaml` | Elasticsearch StatefulSet |
| `template-volumes.yaml` | Persistent volumes for documents and connectors |
| `tail-connector-logs.sh` | Tails connector logs in the terminal, handy for debugging and troubleshooting. |

## Versions

| Component | Version |
|---|---|
| Camunda | 8.9.12 |
| Helm chart | 14.7.0 |
| Keycunda | see `keycunda/pom.xml`, image tag set by `KEYCUNDA_IMAGE` |
| Elasticsearch | 8.19.x |
| PostgreSQL | 16 |
