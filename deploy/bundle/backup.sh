#!/usr/bin/env bash
# ReadyRoom - back up the database and the license volume, while ReadyRoom keeps running.
#
#     ./backup.sh                 # into ./backups
#     ./backup.sh /mnt/backups    # or a directory of your own
#     ./backup.sh --engine podman # Docker or Podman: see lib/engine.sh (default: Docker if its daemon
#                                 # answers, else Podman; or CONTAINER_ENGINE=)
#
# Writes, with a UTC timestamp:
#   readyroom-db-<stamp>.dump        pg_dump of the database, custom format (restore.sh / pg_restore)
#   readyroom-license-<stamp>.tar.gz the license volume: the license, and this installation's id that
#                                    licenses are bound to
# and removes dumps older than BACKUP_RETENTION_DAYS (default 14; 0 keeps every one). Copy the files off
# this host too: a backup on the same disk does not survive the disk. Schedule it with cron, e.g.
#     17 2 * * * cd /opt/readyroom-1.2.3 && ./backup.sh >> backups/backup.log 2>&1
set -euo pipefail

cd "$(dirname "$0")"

say() { printf '%s\n' "$*"; }
die() { printf 'backup.sh: %s\n' "$*" >&2; exit 1; }

# shellcheck source=lib/engine.sh
. lib/engine.sh
engine_args "$@"
[ "${#ARGS[@]}" -le 1 ] || die "usage: ./backup.sh [--engine docker|podman] [directory]"
dest="${ARGS[0]:-backups}"
retention="${BACKUP_RETENTION_DAYS:-14}"
[[ "$retention" =~ ^[0-9]+$ ]] || die "BACKUP_RETENTION_DAYS must be a number of days."
[ -f .env ] || die "no .env here: run this from the directory ReadyRoom was installed from."
engine_select

postgres="$(service_container postgres)"
[ -n "$postgres" ] || die "the postgres container is not running (./compose.sh ps)."

mkdir -p "$dest"
chmod 0700 "$dest"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
umask 077

say "Dumping the database to $dest/readyroom-db-$stamp.dump ..."
# shellcheck disable=SC2016 # expanded by the shell in the container
"$ENGINE" exec -i "$postgres" sh -c 'pg_dump --format=custom --no-owner -U "$POSTGRES_USER" "$POSTGRES_DB"' < /dev/null \
    > "$dest/readyroom-db-$stamp.dump.partial"
mv "$dest/readyroom-db-$stamp.dump.partial" "$dest/readyroom-db-$stamp.dump"

# The license volume, read through a throwaway container of the PostgreSQL image already on this host.
say "Copying the license volume to $dest/readyroom-license-$stamp.tar.gz ..."
image="$("$ENGINE" inspect --format '{{.Config.Image}}' "$postgres")"
"$ENGINE" run --rm -v "$(volume_name ens-license)":/license:ro "$image" tar -C /license -czf - . \
    > "$dest/readyroom-license-$stamp.tar.gz"

if [ "$retention" -gt 0 ]; then
    find "$dest" -maxdepth 1 \( -name 'readyroom-db-*.dump' -o -name 'readyroom-license-*.tar.gz' \) \
        -mtime +"$retention" -print -delete | sed 's/^/Removed old backup /'
fi
find "$dest" -maxdepth 1 -name '*.partial' -mmin +60 -delete

ls -l "$dest/readyroom-db-$stamp.dump" "$dest/readyroom-license-$stamp.tar.gz"
say "Backup complete."
