# Install Camunda on Ubuntu - in 30 minutes

This repository contains the scripts for a self contained installation of Camunda on a single Ubuntu box. This includes Kubernetes, databases and the complete Camunda platform.

This is made for **experimenting, learning and demos**.

**Not for production.** One box lacks the scale, high availability and license that real Camunda
environments need. The script also installs a simplified Identity Provider, as alternative to Keycloak.
The shipped Identity Provider lacks the hardening and security that solutions such as Keycloak and Microsoft Entra Id offer,
which are used in Camunda production environments.

Looking for something lighter? [Camunda 8 Run](https://docs.camunda.io/docs/self-managed/quickstart/developer-quickstart/c8run/)
is a simple local alternative, which runs on Mac and Windows, but lacks Hub and Optimize.

## Requirements

- Ubuntu 26.04 (Server or Desktop), ideally in a VM (Proxmox, VirtualBox) so you can snapshot it
- 12 GB memory (16 GB recommended), 4 vCPU, 32 GB disk (64 GB recommended)
- A **static IP and hostname**, set before you install. Changing either later means reinstalling
  (see [Uninstalling](#uninstalling)).

## Install

1. Install Kubernetes and other dependencies

```bash
./1-install-microk8s.sh
sudo reboot
```

2. Install Camunda

```bash
./2-install-camunda-microk8s.sh
```

A fresh install takes 20–30 minutes: time for a coffee.

## Open it

Camunda is accessed through the browser. There are 2 different approaches to access Camunda:

- **Easiest:** install Camunda on Xubuntu, which ships with a desktop and a browser. Open the
  browser in Xubuntu and go to the URLs below. The browser will warn about the
  self-signed certificate; you can safely ignore the warning and continue.
- **Advanced:** put a reverse proxy in front of the box (see the `true` answer above), so
  Camunda is reachable from any browser. The proxy handles the certificate automatically, so
  there are no warnings. Examples are [Selfie Proxie](https://www.selfieproxy.net/) and
  [Cloudflare](https://www.cloudflare.com).

| Component | URL |
|---|---|
| Camunda Hub | `https://<your-domain>/hub` |
| Operate / Tasklist | `https://<your-domain>/orchestration` |
| Optimize | `https://<your-domain>/optimize` |
| Login, user and secrets management | `https://<your-domain>` |

Log in with the email and password you chose during install.

## Secrets

Add connector secrets at `https://<your-domain>/keycunda/secrets` and click **Apply to cluster**.
Reference them as `=camunda.secrets.NAME` in input mappings.

## Catalog

The installer publishes the standard connector templates to the Catalog in Camunda Hub. To publish
them again, open **Catalog** in the top menu and click **Publish all standard connector templates**. Click again after a new release of the connector templates to
update them.

## Custom connectors

Drop connector JARs into `~/camunda-connectors/connectors`, then restart the connectors pod.

## Uninstalling

- `./uninstall-camunda-microk8s.sh` removes Camunda and all its data. MicroK8s stays.
- `./uninstall-microk8s.sh` removes everything, including MicroK8s. Use it before changing the IP
  or hostname, then start over from step 1.

`~/camunda-docs` and `~/camunda-connectors` are never deleted.

## Good to know

- The first script also installs the [c8ctl](https://github.com/camunda/c8ctl) CLI and the
  [Camunda AI skills](https://github.com/camunda/skills), so AI coding tools such as Claude Code can
  build and operate Camunda processes on this box. It also installs Java, Maven and Docker. 
  Camunda doesn't need them, but they come in handy when building demo portals.
- The second script asks for your version, domain and password, then installs everything.
  Run it again at any time to upgrade: your data is kept, as long as you use the same password
  during installation. Once it's installed, restarting the whole virtual machine takes only about
  5 minutes.
- Run extra Docker containers with `network_mode: host`. Bridge networks do work, but will cause MicroK8s
  to restart and briefly take down every pod when deploying changes.
