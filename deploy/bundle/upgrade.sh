#!/usr/bin/env bash
# ReadyRoom - upgrade to the release in this directory.
#
#     ./upgrade.sh /opt/readyroom-1.2.3     # the directory of the release running now
#     ./upgrade.sh                          # when .env etc. are already in this directory
#
# Copies .env, license/, tls/ and kerberos/ from the previous release's directory (anything not already
# here; nothing is overwritten), loads this release's images and recreates the containers with
# "docker compose up -d". The database lives in a named Docker volume shared by every release, so it
# is kept; the application updates its schema on start.
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

docker compose config --quiet
docker compose up -d --remove-orphans

say ""
say "Upgraded to ReadyRoom $(cat VERSION). Check: docker compose ps   /   docker compose logs -f app"
if [ -n "$previous" ]; then
    say "Run ReadyRoom from $(pwd) from now on; $previous can be removed once you are satisfied."
fi
