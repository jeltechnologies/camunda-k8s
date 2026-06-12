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
| Keycloak | `https://<your-domain>/auth` |
| Zeebe gRPC | `grpc://zeebe.<your-domain>:26500` |

All components are secured with Keycloak OIDC authentication out of the box.

## Requirements

This scripts have been tested in Proxmox virtual machines. I use very modest hardware, Intel i3 8100T. You will need to provision the following in Proxmox:
- Ubuntu 26.04 Server or Desktop
- Minimal 8 GB memory (16 GB recommended)
- Minimum 4 vCPU.
- 32 GB disk (64 GB recommemded)

### Network access to the web applications
- The easiest way to reach the web application is by installing Camunda on Ubuntu Desktop. You can then use the web browser inside the virtual machine, or use Remote Desktop Connection. To reach the server from other machines in your network, you must change the hosts files of these machines. You will get warnings on self signed certificates, which is normal.
- For exposure to the internet, you should use a reverse proxy. This is provided by solutions like Pangolin or Cloudflare. You will then also get a real certifcate. Make sure to not use a secure password during installation. 

## Installation

```bash
# Step 1: install MicroK8s and Helm v4
./1-install-microk8s.sh
sudo reboot

# Step 2: install Camunda
./2-install-camunda-microk8s.sh
```

The second script will prompt for your domain, password, and optional Ollama/GitLab settings, then install everything automatically. Expect 15–20 minutes on first run.

## Architecture

All components run as Kubernetes workloads inside MicroK8s. External dependencies (Keycloak, PostgreSQL, Elasticsearch) are deployed as StatefulSets before the Camunda Helm chart is installed. The nginx ingress controller handles TLS termination and routes all traffic by path prefix — no NodePorts or port forwarding anywhere.

```
Internet → nginx ingress (443)
             ├── /auth          → Keycloak
             ├── /modeler       → Web Modeler
             ├── /orchestration → Zeebe / Operate / Tasklist
             ├── /optimize      → Optimize
             ├── /identity      → Identity
             └── /console       → Console
```

## Custom connectors

Drop connector JARs into `~/camunda-connectors` on the host. 

## Secrets

Create a `connector-secrets.yaml` Kubernetes secret manifest for any credentials they need, then run:

```bash
./update-connector-secrets.sh
```

## Files

| File | Purpose |
|---|---|
| `1-install-microk8s.sh` | Installs MicroK8s and Helm v4 |
| `2-install-camunda-microk8s.sh` | Installs Camunda and all dependencies |
| `configure-env.sh` | Interactive configuration wizard |
| `template-values-camunda.yaml` | Helm values template |
| `template-keycloak.yaml` | Keycloak StatefulSet and Service |
| `template-keycloak-ingress.yaml` | Keycloak ingress |
| `template-postgresql.yaml` | PostgreSQL StatefulSet |
| `template-elasticsearch.yaml` | Elasticsearch StatefulSet |
| `template-volumes.yaml` | Persistent volumes for documents and connectors |
| `update-connector-secrets.sh` | Deploys custom connector secrets |
| `tail-connector-logs.sh` | Tails connector logs in the terminal, handy for debugging and troubleshooting. |

## Versions

| Component | Version |
|---|---|
| Camunda | 8.10 |
| Helm chart | 15.x (requires Helm v4) |
| Keycloak | 26.x |
| Elasticsearch | 8.19.x |
| PostgreSQL | 16 |
