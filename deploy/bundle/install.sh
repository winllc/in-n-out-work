#!/usr/bin/env bash
# ReadyRoom - first install. Safe to run again: it never overwrites .env, the license or the database.
#
#     ./install.sh
#
# 1. Loads the bundled images (images/*.tar), so no registry access is needed.
# 2. Creates .env from env.template, with a random database password, and the license/, tls/ and
#    kerberos/ directories.
# 3. Stops and lists what to edit while .env still has CHANGE_ME values; run it again afterwards.
# 4. Starts ReadyRoom with "docker compose up -d".
set -euo pipefail

cd "$(dirname "$0")"

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

command -v docker > /dev/null 2>&1 || die "docker is not installed or not on PATH."
docker compose version > /dev/null 2>&1 || die "the Docker Compose plugin (docker compose) is required."
docker info > /dev/null 2>&1 || die "cannot reach the Docker daemon (is it running, and may this user use it?)."

# --- images ------------------------------------------------------------------------------------------
load_images() {
    local found=0 tar
    for tar in images/*.tar; do
        [ -e "$tar" ] || continue
        found=1
        say "Loading images from $tar ..."
        docker load -i "$tar"
    done
    if [ "$found" = 0 ]; then
        say "No images/ archive here; images will be pulled from the registry."
    fi
}
load_images

# --- directories -------------------------------------------------------------------------------------
mkdir -p license tls kerberos
chmod 0755 license
chmod 0750 tls kerberos

# --- .env --------------------------------------------------------------------------------------------
random_password() {
    # 32 characters from [A-Za-z0-9]: safe in a .env file and a JDBC connection.
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32 || true
}

if [ -e .env ]; then
    say "Keeping the existing .env."
else
    umask 077
    pw="$(random_password)"
    [ "${#pw}" -eq 32 ] || die "could not generate a database password."
    sed "s/^DB_PASSWORD=CHANGE_ME\$/DB_PASSWORD=${pw}/" env.template > .env
    chmod 0600 .env
    umask 022
    say "Created .env from env.template (with a generated database password)."
fi

if grep -q '^[^#]*CHANGE_ME' .env; then
    say ""
    say "Before ReadyRoom can start, edit $(pwd)/.env and replace every CHANGE_ME:"
    say ""
    grep -n '^[^#]*CHANGE_ME' .env | sed 's/^/    line /'
    say ""
    say "  APPLICATION_BASE_URL   the address users reach ReadyRoom at"
    say "  LDAP_*, APPLICATION_*  your directory: host, service account, where users and groups are,"
    say "                         and at least one administrator DN"
    say "  SPRING_MAIL_*          your SMTP server (or set MANAGEMENT_HEALTH_MAIL_ENABLED=false)"
    say ""
    say "Put the ENS Solutions license at $(pwd)/license/license.lic"
    say "(without one ReadyRoom runs with its core features only)."
    say ""
    say "Then run ./install.sh again."
    exit 0
fi

if [ ! -f license/license.lic ]; then
    say "Note: no license/license.lic yet - ReadyRoom will run with its core features only."
    say "      Copy the license there at any time; it is picked up within 30 seconds."
fi

# --- optional pieces named in COMPOSE_FILE -----------------------------------------------------------
compose_file="$(sed -n 's/^COMPOSE_FILE=//p' .env | tail -n 1)"
case "$compose_file" in
    *docker-compose.tls.yml*)
        [ -f tls/keystore.p12 ] || die "COMPOSE_FILE includes the TLS file but tls/keystore.p12 is missing."
        ;;
esac
case "$compose_file" in
    *docker-compose.windows-auth.yml*)
        [ -f kerberos/krb5.conf ] || die "COMPOSE_FILE includes Windows sign-in but kerberos/krb5.conf is missing."
        [ -f kerberos/http.keytab ] || die "COMPOSE_FILE includes Windows sign-in but kerberos/http.keytab is missing."
        ;;
esac

# --- start -------------------------------------------------------------------------------------------
docker compose config --quiet
docker compose up -d

port="$(sed -n 's/^READYROOM_PORT=//p' .env | tail -n 1)"
say ""
say "ReadyRoom is starting (the first start takes a minute or two while the database schema is created)."
say "  Status:  docker compose ps        (app becomes \"healthy\")"
say "  Logs:    docker compose logs -f app"
say "  Open:    $(sed -n 's/^APPLICATION_BASE_URL=//p' .env | tail -n 1)   (port ${port:-8181} on this host)"
