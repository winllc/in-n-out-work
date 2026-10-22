# ReadyRoom __VERSION__

Workforce check-in/out and attendance for an LDAP-backed organization, by ENS Solutions. This bundle
installs the ReadyRoom server with Docker Compose or Podman. Everything it needs is included: the container
images are in `images/`, so a site without internet access needs nothing else.

Full documentation: https://winllc.github.io/ens-apps-documentation/readyroom/install/ (installation,
configuration, Windows authentication, licensing; audit logging at
https://winllc.github.io/ens-apps-documentation/audit-logging/). For Kubernetes or OpenShift, install
with the Helm chart instead (`readyroom-helm-__VERSION__.tgz` in the release download).

## What you need

- Linux host, 2 CPUs, 4 GB RAM, 10 GB disk, with either container engine:
  - **Docker** Engine 24 or later with Docker Compose 2.24 or later (the `docker compose` plugin, or a
    v2 `docker-compose`; the old Python `docker-compose` v1 cannot read this bundle), or
  - **Podman** 4.4 or later (RHEL/Rocky/Alma 9, Fedora, Ubuntu 24.04) with **podman-compose 1.3 or
    later** (`pip install --user podman-compose`, or your distribution's package if it is that new;
    1.3 is the first to wait for PostgreSQL to be healthy). Rootful or rootless; see [Podman](#podman).
- Your LDAP directory or Active Directory, and a read-only service account for it.
- An SMTP server for absence notifications (optional, see `MANAGEMENT_HEALTH_MAIL_ENABLED`).
- Your ENS Solutions license file (`.lic`), downloaded from your account on the ENS portal: an
  evaluation, or the license issued against your order. Without one ReadyRoom offers only sign-in
  and its license screen.

ReadyRoom keeps its own records in the bundled PostgreSQL 16 container, in the volume
`readyroom_postgres_data`, and its license and installation id in the volume `readyroom_ens-license`.
Its audit trail is in the database too (Administration > Audit log); to send it to Splunk or another
SIEM, set the `ENS_AUDIT_*` lines in `.env` (see `env.template`). The optional audit log file goes to
the volume `readyroom_ens-audit`.

## Verify the download

In the directory with the downloaded files:

```bash
sha256sum -c SHA256SUMS --ignore-missing
gpg --verify SHA256SUMS.asc SHA256SUMS     # with the ENS Solutions release key imported
```

## Install

```bash
tar xzf readyroom-__VERSION__.tar.gz
cd readyroom-__VERSION__
./install.sh
```

The scripts use Docker when it is installed and its daemon answers, and Podman otherwise; each prints
which engine and compose command it chose. To choose, pass `--engine docker` or `--engine podman` to
any of them (`./install.sh --engine podman`), or set `CONTAINER_ENGINE=docker|podman`. `COMPOSE_COMMAND`
overrides the compose command (for example `COMPOSE_COMMAND="podman compose"`).

The first run loads the images, creates `.env` from `env.template` (with a generated database
password) and lists the lines to edit. Edit `.env`, replacing every `CHANGE_ME`:

| Setting | What |
|---|---|
| `APPLICATION_BASE_URL` | The address users reach ReadyRoom at (used in e-mail links) |
| `SPRING_LDAP_URLS`, `LDAP_USERNAME`, `LDAP_PASSWORD` | Your directory (`ldaps://host:636`) and the service account |
| `APPLICATION_USERBASEDN`, `APPLICATION_GROUPS_0_GROUPSBASEDN` | Where users and group trees are |
| `APPLICATION_SUPERUSERDNS_0` | At least one administrator, by full DN |
| `SPRING_MAIL_*`, `APPLICATION_NOTIFICATIONSENDEREMAIL` | Your SMTP server and the From address |

Optionally copy the license to `license/license.lic`, then run `./install.sh` again. It starts
ReadyRoom with `docker compose up -d` (or `podman-compose up -d`); the first start takes a minute or
two. `./compose.sh ps` shows the `app` container as `healthy` when it is ready. If `license/license.lic` is there, it is
copied into the license volume; otherwise `install.sh` prints where to upload it. Open
`APPLICATION_BASE_URL` and sign in with a directory account.

`install.sh` can be run again at any time; it never overwrites `.env`, the database or an installed
license.

### Network address and port

ReadyRoom is published on the host at `READYROOM_BIND_ADDRESS:READYROOM_PORT` from `.env`, by default
**`0.0.0.0:8181`: every network interface**. Until TLS is on (below) that is plain HTTP, and `install.sh`
warns about it. To restrict it, set the address in `.env` and run `./compose.sh up -d`:

- `READYROOM_BIND_ADDRESS=127.0.0.1` keeps it to this host, for a reverse proxy on the same host that
  terminates TLS (proxy to `http://127.0.0.1:8181`);
- one of the host's own addresses publishes it on that interface only.

Use a port of 1024 or above: rootless Podman cannot publish lower ones (unless
`net.ipv4.ip_unprivileged_port_start` is lowered). The container itself always listens on 8181.

### Podman

The same bundle, scripts and compose files work with Podman; Podman needs no daemon. Notes:

- **Images** are named in full (`docker.io/library/postgres:16`, `ghcr.io/...`), as Podman has no
  default registry; `install.sh` loads them from `images/` with `podman load`.
- **podman-compose** is used directly, even where `podman compose` exists: that one prefers Docker's
  `docker-compose` when it is also installed, which then needs the Podman API socket. podman-compose
  does not read `COMPOSE_FILE` from `.env`, so use `./compose.sh` (below) for everyday commands, which
  passes it on; plain `podman-compose ...` would leave out the TLS and Windows sign-in files.
- **Health checks** are run by systemd timers. On a host without systemd they never run, and
  `podman-compose up` waits for PostgreSQL to become healthy forever.
- **Rootless** (as an ordinary user): data is in named volumes, which need no ownership changes. Your
  own group is group 0 in the containers, so for the files in `tls/` and `kerberos/` `chmod 0640` alone
  is enough (no `chgrp 0`). Run `loginctl enable-linger <user>` once so the containers keep running
  after you log out, and `systemctl --user enable podman-restart.service` so containers with
  `restart: unless-stopped` start again after a reboot. Ports below 1024 need the sysctl above.
- **SELinux** (RHEL, Fedora): the bind mounts of `tls/` and `kerberos/` carry `:Z`, so they are
  relabelled for the container. Docker on hosts without SELinux ignores it.
- **Starting at boot** without `podman-restart.service`: generate units with `podman generate systemd
  --new --files --name readyroom_app_1` (and `readyroom_postgres_1`), or write Quadlet `.container`
  files; either way, keep the volume names above.
- `podman-compose` names the containers `readyroom_app_1` and `readyroom_postgres_1` (Docker:
  `readyroom-app-1`); the volumes are named the same under both.

## License

Signed in as an administrator (`APPLICATION_SUPERUSERDNS_*`), open **License** in the menu
(`APPLICATION_BASE_URL/app/admin/license`). It shows what is installed and what it includes, how many
people ReadyRoom tracks against the licensed size, and this installation's id (for example
`RR-7F3K-29QD`): enter it on the ENS portal to bind a license to this installation. Upload a new or
renewed license there; it takes effect at once.

| License | ReadyRoom |
|---|---|
| Valid, or expired but within its grace period | Everything the license includes; administrators see a renewal reminder |
| Expired past grace | Read-only: everything visible, nothing can be changed, the Windows client's check-ins are refused and the scheduled jobs pause |
| None, or not valid for this installation | Only sign-in and the license screen |

The license lives in the `readyroom_ens-license` volume with the installation id, and is kept across
upgrades. Never remove that volume (`./compose.sh down -v`): the installation would get a new id,
and a license bound to the old one would stop working. Licenses from before this release are in an
older format that is no longer read: download the license again from the portal.

**Health and mail.** The health check includes the mail server. If it cannot be reached, ReadyRoom
reports `DOWN` and the container shows as unhealthy, although everything except e-mail works. Fix the
`SPRING_MAIL_*` settings, or set `MANAGEMENT_HEALTH_MAIL_ENABLED=false` if you do not use e-mail.

## TLS, the Windows client and Windows sign-in

Out of the box ReadyRoom serves plain HTTP on port 8181 (`READYROOM_PORT`). That is enough for
browsers with username and password sign-in, for example behind a reverse proxy that terminates TLS.

The **ReadyRoom Windows client** (in `windows-client/`) reports each workstation's logon, lock, unlock
and logoff. Its check-in, lock and unlock calls sign in with the user's client certificate or Windows
logon, which needs ReadyRoom itself to speak TLS:

1. Put your server keystore at `tls/keystore.p12` and the CA certificates for client certificates at
   `tls/truststore.p12` (readable by group 0: `chgrp 0 tls/*.p12 && chmod 0640 tls/*.p12`). Both are
   required: the image contains neither, and ReadyRoom refuses to start with TLS on until both are
   there. Put only the CAs that issue your users' certificates in the truststore.
2. In `.env`: `SSL_ENABLED=true`, `SERVER_SSL_KEYSTOREPASSWORD`, `SERVER_SSL_TRUSTSTOREPASSWORD`,
   `COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml`, and an `https://` `APPLICATION_BASE_URL`.
3. `./compose.sh up -d`.

On each workstation, from an elevated PowerShell prompt in a copy of `windows-client/`:

```powershell
.\Install-InOutWorker.ps1 -BaseUrl "https://readyroom.example.com:8181/api/check"
```

Add `-Authentication Windows` or `-Authentication Auto` for Windows sign-in, which also needs, on the
server:

1. A service account and SPN in Active Directory, and its keytab (`setspn`, `ktpass`; see Windows
   authentication in the documentation).
2. `kerberos/krb5.conf` and `kerberos/http.keytab` (keytab: `chgrp 0`, `chmod 0640`).
3. In `.env`: `APPLICATION_WINDOWSAUTH_ENABLED=true`, `APPLICATION_WINDOWSAUTH_SERVICEPRINCIPAL`, and
   `COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml:docker-compose.windows-auth.yml`.

## Upgrade

Unpack the new release next to the current one and run its `upgrade.sh`, naming the current
directory:

```bash
tar xzf readyroom-<new-version>.tar.gz
cd readyroom-<new-version>
./upgrade.sh ../readyroom-__VERSION__
```

It copies `.env`, `license/`, `tls/` and `kerberos/`, loads the new images and recreates the
containers, with the engine chosen as for `install.sh` (`./upgrade.sh --engine podman ../readyroom-...`).
The database and license volumes are kept; if the license volume holds no license yet,
`license/license.lic` is copied in, and otherwise `upgrade.sh` says where to upload one. Back up first,
from the current release's directory: `./backup.sh` (see [Backups](#backups)).

There are no database scripts to run by hand: ReadyRoom migrates its schema itself when it starts
(Flyway), before it serves anything, and `./compose.sh ps` shows `app` as `healthy` once that is done.
The first start of a release with Flyway on an existing database records the schema as it was and brings
it up to date, including the indexes and the audit trail's append-only trigger that the `migrations/`
scripts of earlier releases added; on a database where those scripts never ran it builds the indexes,
which on a large check-in table makes that one start take a few minutes. Check it with
`./compose.sh logs app | grep -i flyway`.

**Upgrading to this release** (Docker and Podman support):

- `.env` is copied as it is, so an existing install keeps its `READYROOM_BIND_ADDRESS` and
  `READYROOM_PORT`: one published on `127.0.0.1` stays so. Only `.env` files made from this release's
  `env.template` start with `0.0.0.0`; `upgrade.sh` prints the address in use. A `.env` without
  `READYROOM_BIND_ADDRESS` publishes on `0.0.0.0`, as earlier releases did.
- An existing Docker install needs no changes: the scripts find Docker, and the project, container and
  volume names are unchanged. The PostgreSQL image is now named `docker.io/library/postgres:16`, the
  same image as `postgres:16`.
- Moving an install from Docker to Podman is a backup and restore, not an upgrade: the engines keep
  separate volumes. Run `./backup.sh` with Docker, stop it (`docker compose down`), run
  `./install.sh --engine podman` with the same `.env`, then `./restore.sh --engine podman ...`.

Re-run `Install-InOutWorker.ps1` from the new `windows-client/` on every workstation (without
arguments it keeps the existing configuration). Besides installing the new script and tasks, it
corrects the permissions an older client left on `%ProgramData%\InOutWorker` (see the Windows client
documentation).

## Backups

`./backup.sh` (`--engine` as for `install.sh`) backs up, while ReadyRoom keeps running, the database (`pg_dump`, custom format) and the
license volume (the license and this installation's id, which licenses are bound to) into `backups/`, or
the directory given, and removes backups older than `BACKUP_RETENTION_DAYS` days (default 14; `0` keeps
them all). Schedule it, and copy the files off this host (from cron, make sure `PATH` includes the engine
and `podman-compose`, often `~/.local/bin`, and set `CONTAINER_ENGINE` when both engines are installed):

```bash
17 2 * * * cd /opt/readyroom-__VERSION__ && ./backup.sh >> backups/backup.log 2>&1
```

`./restore.sh backups/readyroom-db-<stamp>.dump [backups/readyroom-license-<stamp>.tar.gz]` stops
ReadyRoom, replaces the database (and the license volume) with the backup's, and starts it again; anything
recorded since the backup is lost. Restore into the same or a newer release: on start, ReadyRoom migrates an
older backup's schema forward.

## Everyday commands

`./compose.sh` runs the compose command `install.sh` chose (`docker compose` or `podman-compose`), with
the files `COMPOSE_FILE` names in `.env`:

```bash
./compose.sh ps                   # status
./compose.sh logs -f app          # application log
./compose.sh up -d                # apply changes to .env (recreates what changed)
./compose.sh down                 # stop (the database and license volumes are kept)
```

With Docker, `docker compose ...` in this directory does the same. Never add `-v` to `down`: it deletes
the database and license volumes.

## Contents

| | |
|---|---|
| `install.sh`, `upgrade.sh` | Install and upgrade |
| `backup.sh`, `restore.sh` | Back up and restore the database and the license volume |
| `compose.sh`, `lib/engine.sh` | Everyday compose commands with Docker or Podman; the engine choice all scripts share |
| `docker-compose.yml` | The deployment: ReadyRoom and PostgreSQL 16 |
| `docker-compose.tls.yml`, `docker-compose.windows-auth.yml` | Optional TLS keystore and Windows sign-in mounts |
| `env.template` | Every setting, commented; copied to `.env` |
| `license/` | Where `license.lic` can go for `install.sh` to copy in |
| `images/` | The container images (`docker load` / `podman load`) |
| `windows-client/` | The Windows client and its installer |
