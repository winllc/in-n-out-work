#!/usr/bin/env bash
# ReadyRoom - restore a backup made by backup.sh. Replaces the database (and, if given, the license volume)
# with the backup's: everything recorded since that backup is lost.
#
#     ./restore.sh backups/readyroom-db-20261017T021700Z.dump [backups/readyroom-license-20261017T021700Z.tar.gz]
#
# Stops ReadyRoom, restores, and starts it again; on start Flyway brings an older backup's schema up to
# date. Restore into the same or a newer ReadyRoom release, never an older one.
set -euo pipefail

cd "$(dirname "$0")"

say() { printf '%s\n' "$*"; }
die() { printf 'restore.sh: %s\n' "$*" >&2; exit 1; }

dump="${1:-}"
license="${2:-}"
[ -n "$dump" ] || { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[ -f "$dump" ] || die "$dump not found."
[ -z "$license" ] || [ -f "$license" ] || die "$license not found."
[ -f .env ] || die "no .env here: run this from the directory ReadyRoom was installed from."

say "This replaces ReadyRoom's database with $dump${license:+ and the license volume with $license}."
say "Everything recorded since that backup is lost."
if [ "${RESTORE_YES:-}" != yes ]; then
    read -r -p "Type yes to continue: " answer
    [ "$answer" = yes ] || die "not restored."
fi

say "Stopping ReadyRoom ..."
docker compose stop app
docker compose up -d postgres
for _ in $(seq 1 30); do
    docker compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > /dev/null 2>&1 && break
    sleep 2
done

say "Restoring the database ..."
# --clean --if-exists drops what is there first; one transaction, so a failed restore changes nothing.
docker compose exec -T postgres sh -c \
    'pg_restore --clean --if-exists --no-owner --single-transaction --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < "$dump"

if [ -n "$license" ]; then
    say "Restoring the license volume ..."
    image="$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q postgres)")"
    docker run --rm -i -v readyroom_ens-license:/license "$image" \
        sh -c 'find /license -mindepth 1 -delete && tar -C /license -xzf - && chown -R 185:0 /license' < "$license"
fi

say "Starting ReadyRoom ..."
docker compose up -d
say "Restored. Check: docker compose ps   /   docker compose logs -f app"
