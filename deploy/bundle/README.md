# ReadyRoom __VERSION__

Workforce check-in/out and attendance for an LDAP-backed organization, by ENS Solutions. This bundle
installs the ReadyRoom server with Docker Compose. Everything it needs is included: the container
images are in `images/`, so a site without internet access needs nothing else.

Full documentation: https://winllc.github.io/ens-apps-documentation/readyroom/install/ (installation,
configuration, Windows authentication, licensing; audit logging at
https://winllc.github.io/ens-apps-documentation/audit-logging/). For Kubernetes or OpenShift, install
with the Helm chart instead (`readyroom-helm-__VERSION__.tgz` in the release download).

## What you need

- Linux host with Docker Engine 24 or later and the Docker Compose plugin (`docker compose`), 2 CPUs,
  4 GB RAM, 10 GB disk.
- Your LDAP directory or Active Directory, and a read-only service account for it.
- An SMTP server for absence notifications (optional, see `MANAGEMENT_HEALTH_MAIL_ENABLED`).
- Your ENS Solutions license file (`.lic`), downloaded from your account on the ENS portal: an
  evaluation, or the license issued against your order. Without one ReadyRoom offers only sign-in
  and its license screen.

ReadyRoom keeps its own records in the bundled PostgreSQL 16 container, in the Docker volume
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

The first run loads the images, creates `.env` from `env.template` (with a generated database
password) and lists the lines to edit. Edit `.env`, replacing every `CHANGE_ME`:

| Setting | What |
|---|---|
| `APPLICATION_BASE_URL` | The address users reach ReadyRoom at (used in e-mail links) |
| `LDAP_HOST`, `LDAP_PORT`, `LDAP_USERNAME`, `LDAP_PASSWORD` | Your directory and the service account (`SPRING_LDAP_URLS=ldaps://...` for LDAPS) |
| `APPLICATION_USERBASEDN`, `APPLICATION_GROUPS_0_GROUPSBASEDN` | Where users and group trees are |
| `APPLICATION_SUPERUSERDNS_0` | At least one administrator, by full DN |
| `SPRING_MAIL_*`, `APPLICATION_NOTIFICATIONSENDEREMAIL` | Your SMTP server and the From address |

Optionally copy the license to `license/license.lic`, then run `./install.sh` again. It starts
ReadyRoom with `docker compose up -d`; the first start takes a minute or two. `docker compose ps`
shows the `app` container as `healthy` when it is ready. If `license/license.lic` is there, it is
copied into the license volume; otherwise `install.sh` prints where to upload it. Open
`APPLICATION_BASE_URL` and sign in with a directory account.

`install.sh` can be run again at any time; it never overwrites `.env`, the database or an installed
license.

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
upgrades. Never remove that volume (`docker compose down -v`): the installation would get a new id,
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
3. `docker compose up -d`.

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
containers. The database and license volumes are kept; if the license volume holds no license yet,
`license/license.lic` is copied in, and otherwise `upgrade.sh` says where to upload one. Back the
database up first:

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > readyroom-backup.sql
```

There are no database scripts to run by hand: ReadyRoom migrates its schema itself when it starts
(Flyway), before it serves anything, and `docker compose ps` shows `app` as `healthy` once that is done.
The first start of a release with Flyway on an existing database records the schema as it was and brings
it up to date, including the indexes and the audit trail's append-only trigger that the `migrations/`
scripts of earlier releases added; on a database where those scripts never ran it builds the indexes,
which on a large check-in table makes that one start take a few minutes. Check it with
`docker compose logs app | grep -i flyway`.

Re-run `Install-InOutWorker.ps1` from the new `windows-client/` on every workstation (without
arguments it keeps the existing configuration). Besides installing the new script and tasks, it
corrects the permissions an older client left on `%ProgramData%\InOutWorker` (see the Windows client
documentation).

## Everyday commands

```bash
docker compose ps                 # status
docker compose logs -f app        # application log
docker compose up -d              # apply changes to .env (recreates what changed)
docker compose down               # stop (the database and license volumes are kept)
```

## Contents

| | |
|---|---|
| `install.sh`, `upgrade.sh` | Install and upgrade |
| `docker-compose.yml` | The deployment: ReadyRoom and PostgreSQL 16 |
| `docker-compose.tls.yml`, `docker-compose.windows-auth.yml` | Optional TLS keystore and Windows sign-in mounts |
| `env.template` | Every setting, commented; copied to `.env` |
| `license/` | Where `license.lic` can go for `install.sh` to copy in |
| `images/` | The container images (`docker load`) |
| `windows-client/` | The Windows client and its installer |
