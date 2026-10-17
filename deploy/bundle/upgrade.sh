#!/usr/bin/env bash
# ReadyRoom - upgrade to the release in this directory.
#
#     ./upgrade.sh /opt/readyroom-1.2.3     # the directory of the release running now
#     ./upgrade.sh                          # when .env etc. are already in this directory
#
# Copies .env, license/, tls/ and kerberos/ from the previous release's directory (anything not already
# here; nothing is overwritten), loads this release's images and recreates the containers with
# "docker compose up -d". The database and the license (with this installation's id) live in named
# Docker volumes shared by every release, so they are kept; the application updates its schema on
# start. If the license volume holds no license yet, license/license.lic is copied in.
#
# Back up the database first:
#     docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > readyroom-backup.sql
set -euo pipefail

cd "$(dirname "$0")"

say() { printf '%s\n' "$*"; }
die() { printf 'upgrade.sh: %s\n' "$*" >&2; exit 1; }

command -v docker > /dev/null 2>&1 || die "docker is not installed or not on PATH."
docker compose version > /dev/null 2>&1 || die "the Docker Compose plugin (docker compose) is required."
docker info > /dev/null 2>&1 || die "cannot reach the Docker daemon."

previous="${1:-}"
if [ -n "$previous" ]; then
    [ -d "$previous" ] || die "$previous is not a directory."
    previous="$(cd "$previous" && pwd)"
    [ "$previous" != "$(pwd)" ] || previous=""
fi

if [ -n "$previous" ]; then
    [ -f "$previous/.env" ] || die "$previous/.env not found: is that the directory ReadyRoom was installed from?"
    if [ -e .env ]; then
        say "Keeping the .env already here."
    else
        cp -p "$previous/.env" .env
        say "Copied .env from $previous."
    fi
    for dir in license tls kerberos; do
        [ -d "$previous/$dir" ] || continue
        # Never overwrite a file already in the new directory (license/README.txt, for one).
        (cd "$previous/$dir" && find . -type f) | while IFS= read -r f; do
            [ -e "$dir/$f" ] && continue
            mkdir -p "$dir/$(dirname "$f")"
            cp -p "$previous/$dir/$f" "$dir/$f"
        done
    done
    say "Copied license/, tls/ and kerberos/ from $previous (existing files kept)."
fi

[ -f .env ] || die "no .env here. Pass the previous release's directory, or run ./install.sh for a first install."

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

mkdir -p license tls kerberos

for tar in images/*.tar; do
    [ -e "$tar" ] || continue
    say "Loading images from $tar ..."
    docker load -i "$tar"
done

# Settings added to env.template since the previous release are not in the old .env. They all have
# defaults, but list them so they can be reviewed.
new_keys="$(
    sed -n 's/^#\{0,1\}\([A-Z][A-Z0-9_]*\)=.*/\1/p' env.template | sort -u | while read -r key; do
        grep -q "^#\{0,1\}${key}=" .env || printf '    %s\n' "$key"
    done
)"
if [ -n "$new_keys" ]; then
    say "Settings in this release's env.template that your .env does not mention (defaults apply):"
    say "$new_keys"
fi

# Releases before this one fell back to a development keystore and truststore inside the image; there
# are none now, and ReadyRoom refuses to start with TLS on until both are supplied.
compose_file="$(sed -n 's/^COMPOSE_FILE=//p' .env | tail -n 1)"
if [ "$(sed -n 's/^SSL_ENABLED=//p' .env | tail -n 1)" = true ]; then
    case "$compose_file" in
        *docker-compose.tls.yml*) ;;
        *) die "SSL_ENABLED=true needs your own keystore and truststore: add docker-compose.tls.yml to COMPOSE_FILE and put them in tls/ (see env.template). The image no longer contains any." ;;
    esac
fi
case "$compose_file" in
    *docker-compose.tls.yml*)
        [ -f tls/keystore.p12 ] || die "COMPOSE_FILE includes the TLS file but tls/keystore.p12 is missing."
        [ -f tls/truststore.p12 ] || die "COMPOSE_FILE includes the TLS file but tls/truststore.p12 (the CAs whose client certificates may sign in) is missing."
        ;;
esac

docker compose config --quiet
docker compose up -d --remove-orphans

say ""
install_license
say ""
say "Upgraded to ReadyRoom $(cat VERSION). Check: docker compose ps   /   docker compose logs -f app"
if [ -n "$previous" ]; then
    say "Run ReadyRoom from $(pwd) from now on; $previous can be removed once you are satisfied."
fi
