#!/usr/bin/env bash
# ReadyRoom - restore a backup made by backup.sh. Replaces the database (and, if given, the license volume)
# with the backup's: everything recorded since that backup is lost.
#
#     ./restore.sh backups/readyroom-db-20261017T021700Z.dump [backups/readyroom-license-20261017T021700Z.tar.gz]
#
# Docker or Podman: --engine docker|podman, or CONTAINER_ENGINE; otherwise Docker if its daemon answers,
# else Podman (see lib/engine.sh).
#
# Stops ReadyRoom, restores, and starts it again; on start Flyway brings an older backup's schema up to
# date. Restore into the same or a newer ReadyRoom release, never an older one.
set -euo pipefail

cd "$(dirname "$0")"

say() { printf '%s\n' "$*"; }
die() { printf 'restore.sh: %s\n' "$*" >&2; exit 1; }

# shellcheck source=lib/engine.sh
. lib/engine.sh
engine_args "$@"
dump="${ARGS[0]:-}"
license="${ARGS[1]:-}"
if [ -z "$dump" ] || [ "${#ARGS[@]}" -gt 2 ]; then
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi
[ -f "$dump" ] || die "$dump not found."
[ -z "$license" ] || [ -f "$license" ] || die "$license not found."
[ -f .env ] || die "no .env here: run this from the directory ReadyRoom was installed from."
engine_select

say "This replaces ReadyRoom's database with $dump${license:+ and the license volume with $license}."
say "Everything recorded since that backup is lost."
if [ "${RESTORE_YES:-}" != yes ]; then
    read -r -p "Type yes to continue: " answer
    [ "$answer" = yes ] || die "not restored."
fi

say "Stopping ReadyRoom ..."
compose stop app
compose up -d postgres
for _ in $(seq 1 30); do
    # shellcheck disable=SC2016 # expanded by the shell in the container
    service_exec postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < /dev/null > /dev/null 2>&1 && break
    sleep 2
done

say "Restoring the database ..."
# --clean --if-exists drops what is there first; one transaction, so a failed restore changes nothing.
# shellcheck disable=SC2016 # expanded by the shell in the container
service_exec postgres sh -c \
    'pg_restore --clean --if-exists --no-owner --single-transaction --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < "$dump"

if [ -n "$license" ]; then
    say "Restoring the license volume ..."
    image="$("$ENGINE" inspect --format '{{.Config.Image}}' "$(service_container postgres)")"
    "$ENGINE" run --rm -i -v "$(volume_name ens-license)":/license "$image" \
        sh -c 'find /license -mindepth 1 -delete && tar -C /license -xzf - && chown -R 185:0 /license' < "$license"
fi

say "Starting ReadyRoom ..."
compose up -d
say "Restored. Check: ./compose.sh ps   /   ./compose.sh logs -f app"
