# Camunda 8 Self Contained Installation

A fully automated installation of Camunda 8 on a single Ubuntu box for experimenting, learning and demos.

Camunda itself is built to power mission-critical orchestration at real scale, on a properly
provisioned, licensed, high-availability cluster - that's where it truly excels. **This project
cannot be used in production**, because a single Ubuntu box lacks the robustness, scale, high
availability and license that enterprise environments need.

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
| Zeebe gRPC | `grpc://zeebe.<your-domain>:26500` |
| Swagger UI | `https://<your-domain>/orchestration/swagger` *(disabled by default)* |
| Keycunda | `https://<your-domain>/keycunda` |

All components are secured with OIDC authentication, provided by a purpose-built
identity provider called **Keycunda**, which is used for user management and Kubernetes cluster management.

Keycunda is a **demo-grade** OIDC provider — it must not be used in production, because it lacks the security level that an
enterprise Identity Provider like Keycloak can offer.

## Requirements

This scripts have been tested in Proxmox virtual machines. I use very modest hardware, Intel i3 8100T. You will need to provision the following in Proxmox:
- Ubuntu 26.04 Server or Desktop
- Minimal 8 GB memory (16 GB recommended)
- Minimum 4 vCPU.
- 32 GB disk (64 GB recommemded)
- A static IP address and a static hostname, set before you install. MicroK8s bakes both into its
  cluster certificates at install time; changing either one afterwards (e.g. a DHCP lease reassigning
  the IP) breaks TLS between the Kubernetes API server and the node and is not something you can fix
  by editing config — the fix is reinstalling. If you do need to change the IP or hostname later, run
  `./uninstall-microk8s.sh` first (removes MicroK8s, Helm and everything else `1-install-microk8s.sh`
  set up, including any Camunda installation on top of it), change the IP/hostname at the OS level,
  then start over from `./1-install-microk8s.sh`.

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

The second script fetches the [Camunda 8 Helm Chart Version Matrix](https://helm.camunda.io/camunda-platform/version-matrix/)
and lets you pick the latest alpha or latest stable Camunda/Helm chart version with one keystroke,
or enter both manually (also the fallback if the page can't be reached).

After this the script prompts for domain, password, and optional Ollama/GitLab settings, then install everything automatically. Expect 15–20 minutes on first run.

## Uninstalling

Two scripts are provided, for two different levels of teardown:

- **`./uninstall-camunda-microk8s.sh`** removes all Camunda components and their data — the Helm
  release, the `camunda` namespace (Keycunda, PostgreSQL, Elasticsearch and everything else in it),
  so **all data in PostgreSQL is lost**. MicroK8s itself is left installed, so you can immediately
  run `./2-install-camunda-microk8s.sh` again for a clean reinstall.
- **`./uninstall-microk8s.sh`** removes MicroK8s, Helm and everything else `1-install-microk8s.sh`
  set up. Since this deletes the whole Kubernetes cluster, **it also removes Camunda and its data**
  the same as the script above — there is no way to keep Camunda running without MicroK8s under it.
  Use this one when you need to change the machine's static IP address or hostname (see
  [Requirements](#requirements)): MicroK8s bakes both into its cluster certificates at install time
  with no supported way to change them afterwards, so the fix is to remove it entirely, change the
  IP/hostname at the OS level, and run `./1-install-microk8s.sh` again from scratch.

Both scripts leave `~/camunda-docs` and `~/camunda-connectors` in place. These hold your own
documents and custom connector JARs, not generated state — `2-install-camunda-microk8s.sh` recreates
the volumes pointing at these same folders on every install, so whatever's already there is
redeployed automatically without having to be re-added by hand. Delete them yourself if you actually
want a clean slate.

## Architecture

Camunda is deployed in a single-node Kubernetes cluster. All components run as Kubernetes workloads inside MicroK8s. External dependencies for Camunda (Keycunda,
PostgreSQL, Elasticsearch) are deployed before the Camunda Helm chart is installed. The nginx
ingress controller handles TLS termination and routes all traffic by path prefix — no NodePorts
or port forwarding anywhere.

## Custom connectors

Drop connector JARs into `~/camunda-connectors` on the host. 

## Secrets

Secrets are managed with Keycunda, accessible at https://your-camunda/keycunda
