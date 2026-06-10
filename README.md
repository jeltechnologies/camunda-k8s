# Camunda 8 Self Contained Installation

A fully automated installation of Camunda 8 on a single Ubuntu VM using MicroK8s — with every component running and accessible via real URLs, no port forwarding required.

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


## Why this exists

Most local Camunda setups do not contain Web Modeler and Optimize or use Docker Compose with port forwarding — each component on a different port, no shared authentication, and no ingress. This project takes a different approach:

- **Real URLs** — every component is accessible at a proper path on your domain, the same way a production deployment works
- **Full platform** — Web Modeler and Optimize are included, which are often left out of local setups due to complexity
- **If I could turn back time** - Dare to make mistakes, or use restore functions after a demo. Use the Proxmox or VirtualBox snapshots and backup functions.
- **Two script only** — run two scripts and one restart, taking max 30 minutes.
- **Re-runnable** — running the install script again upgrades the existing installation, preserving your data

## Requirements

This scripts have been tested in Proxmox virtual machines. I use very modest hardware, Intel i3 8100T. You will need to provision the following in Proxmox:
- Ubuntu 26.04 Server or Desktop
- Minimal 8 GB memory (16 GB recommended)
- Minimum 4 vCPU.
- 32 GB disk (64 GB recommemded)

### Network access to the web applications
- Install Camunda on Ubuntu Desktop and use a web browser inside the virtual machine, to reach the server from within your network. You may also change the hosts file of your machines, so the domain points chosen during installation points to the IP of your virtual machine. You will get warnings on self signed certificates, which is normal.
- For exposure to the internet you should use a reverse proxy provided by solutions like Pangolin or Cloudflare. They will provide a real certifcate. You then must use a secure password during installation. 

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

Drop connector JARs into `~/camunda-connectors` on the host. Create a `connector-secrets.yaml` Kubernetes secret manifest for any credentials they need, then run:

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
| `tail-connector-logs.sh` | Streams connector pod logs |

## Versions

| Component | Version |
|---|---|
| Camunda | 8.10 |
| Helm chart | 15.x (requires Helm v4) |
| Keycloak | 26.x |
| Elasticsearch | 8.19.x |
| PostgreSQL | 16 |
