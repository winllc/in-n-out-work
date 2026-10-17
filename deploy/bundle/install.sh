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
# 5. Copies license/license.lic into the license volume if there is one and the volume has none;
#    otherwise says where to upload the license (the license screen, /app/admin/license).
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
    say "Optionally put the ENS Solutions license at $(pwd)/license/license.lic; it can also be"
    say "uploaded on ReadyRoom's license screen once it is running."
    say ""
    say "Then run ./install.sh again."
    exit 0
fi

# --- optional pieces named in COMPOSE_FILE -----------------------------------------------------------
compose_file="$(sed -n 's/^COMPOSE_FILE=//p' .env | tail -n 1)"
# The image holds no keystore or truststore, and ReadyRoom refuses to start with TLS on without them.
if [ "$(sed -n 's/^SSL_ENABLED=//p' .env | tail -n 1)" = true ]; then
    case "$compose_file" in
        *docker-compose.tls.yml*) ;;
        *) die "SSL_ENABLED=true needs your own keystore and truststore: add docker-compose.tls.yml to COMPOSE_FILE and put them in tls/." ;;
    esac
fi
case "$compose_file" in
    *docker-compose.tls.yml*)
        [ -f tls/keystore.p12 ] || die "COMPOSE_FILE includes the TLS file but tls/keystore.p12 is missing."
        [ -f tls/truststore.p12 ] || die "COMPOSE_FILE includes the TLS file but tls/truststore.p12 (the CAs whose client certificates may sign in) is missing."
        ;;
esac
case "$compose_file" in
    *docker-compose.windows-auth.yml*)
        [ -f kerberos/krb5.conf ] || die "COMPOSE_FILE includes Windows sign-in but kerberos/krb5.conf is missing."
        [ -f kerberos/http.keytab ] || die "COMPOSE_FILE includes Windows sign-in but kerberos/http.keytab is missing."
        ;;
esac

# --- license -----------------------------------------------------------------------------------------
# The license, the installation id it can be bound to and the licensing state live in the ens-license
# volume (/var/lib/ens/readyroom in the app container), which every release shares. license/license.lic
# is copied in when the volume holds no license yet; after that, licenses are replaced on the license
# screen, so an older file left here never overwrites a newer one installed there.
license_screen() {
    printf '%s/app/admin/license' "$(sed -n 's/^APPLICATION_BASE_URL=//p' .env | tail -n 1)"
}

install_license() {
    local target=/var/lib/ens/readyroom/license.lic installed=0
    if docker compose exec -T app test -s "$target" > /dev/null 2>&1; then
        installed=1
    fi
    if [ ! -f license/license.lic ]; then
        if [ "$installed" = 1 ]; then
            say "License: keeping the one installed in the license volume."
        else
            say "No license is installed yet, so ReadyRoom offers only sign-in and its license screen."
            say "Sign in as an administrator and upload the license file (from your ENS portal account) at:"
            say "    $(license_screen)"
        fi
        return 0
    fi
    if ! grep -q -- '-----BEGIN ENS LICENSE-----' license/license.lic; then
        say "license/license.lic is a license in the old format, which this release does not read."
        say "Download the license from your account on the ENS portal and upload it at:"
        say "    $(license_screen)"
        return 0
    fi
    if [ "$installed" = 1 ]; then
        say "License: one is already installed in the license volume, so license/license.lic was not copied."
        say "         To replace it, upload the new file at $(license_screen)"
        return 0
    fi
    # Written by the app's own user (so it can read and later replace it), then moved into place; the
    # same as "docker compose cp license/license.lic app:$target" without the ownership fix-up.
    if ! docker compose exec -T app sh -c 'umask 007 && cat > "$1.tmp" && mv "$1.tmp" "$1"' sh "$target" \
            < license/license.lic; then
        say "Could not copy license/license.lic into the app container (is it running? docker compose ps)."
        say "Run this script again once it is, or upload the license at $(license_screen)"
        return 0
    fi
    say "Copied license/license.lic into the license volume; ReadyRoom uses it at once."
    say "Check it, or replace it later, at $(license_screen)"
}

# --- start -------------------------------------------------------------------------------------------
docker compose config --quiet
docker compose up -d

say ""
install_license

port="$(sed -n 's/^READYROOM_PORT=//p' .env | tail -n 1)"
say ""
say "ReadyRoom is starting (the first start takes a minute or two while the database schema is created)."
say "  Status:  docker compose ps        (app becomes \"healthy\")"
say "  Logs:    docker compose logs -f app"
say "  Open:    $(sed -n 's/^APPLICATION_BASE_URL=//p' .env | tail -n 1)   (port ${port:-8181} on this host)"
