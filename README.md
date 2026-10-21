# Camunda 8 Self Contained Installation

Install a complete Camunda 8 platform on a single Ubuntu box for experimenting, learning and demos.

**Not for production.** One box lacks the scale, high availability and license that real Camunda
environments need. The bundled identity provider, **Keycunda**, is demo-grade too.

Looking for something lighter? [Camunda 8 Run](https://docs.camunda.io/docs/self-managed/quickstart/developer-quickstart/c8run/)
is a simple local alternative, but it does not include Web Modeler or Optimize.

## Requirements

- Ubuntu 26.04 (Server or Desktop), ideally in a VM (Proxmox, VirtualBox) so you can snapshot it
- 8 GB memory (16 GB recommended), 4 vCPU, 32 GB disk (64 GB recommended)
- A **static IP and hostname**, set before you install. Changing either later means reinstalling
  (see [Uninstalling](#uninstalling)).

## Install

```bash
./1-install-microk8s.sh
sudo reboot

./2-install-camunda-microk8s.sh
```

The first script also installs the [c8ctl](https://github.com/camunda/c8ctl) CLI and the
[Camunda AI skills](https://github.com/camunda/skills), so AI coding tools such as Claude Code can
build and operate Camunda processes on this box.

It also installs Java, Maven and Docker. Camunda doesn't need them, but they come in handy when
building demo portals.

The second script asks for your version, domain and password, then installs everything.
A fresh install takes 20–30 minutes: time for a coffee. Run it again at any time to upgrade: your
data is kept, as long as you use the same password during installation. Once it's installed,
restarting the whole virtual machine takes only about 5 minutes.

If your domain points to a reverse proxy (Cloudflare, Pangolin, ...), answer `true` when asked.

## Open it

| Component | URL |
|---|---|
| Camunda Hub | `https://<your-domain>/hub` |
| Operate / Tasklist | `https://<your-domain>/orchestration` |
| Optimize | `https://<your-domain>/optimize` |
| Identity | `https://<your-domain>/identity` |
| Keycunda (users, API clients, secrets) | `https://<your-domain>/keycunda` |
| Zeebe gRPC | `grpc://zeebe.<your-domain>:26500` |

Log in with the email and password you chose during install. The certificate is self-signed, so
expect a browser warning. To reach the box from other machines, add its IP and domain to their
hosts file.

## Secrets

Add connector secrets at `https://<your-domain>/keycunda/secrets` and click **Apply to cluster**.
Reference them as `{{secrets.NAME}}`, or on 8.10 as `=camunda.secrets.NAME` in input mappings.

## Custom connectors

Drop connector JARs into `~/camunda-connectors` and re-run step 2.

## Uninstalling

- `./uninstall-camunda-microk8s.sh` removes Camunda and all its data. MicroK8s stays.
- `./uninstall-microk8s.sh` removes everything, including MicroK8s. Use it before changing the IP
  or hostname, then start over from step 1.

`~/camunda-docs` and `~/camunda-connectors` are never deleted.

## Good to know

- Run extra Docker containers with `network_mode: host` only. Bridge networks can make MicroK8s
  restart and briefly take down every pod.
