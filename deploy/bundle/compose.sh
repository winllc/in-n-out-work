#!/usr/bin/env bash
# ReadyRoom - the compose command for this bundle, whichever engine runs it (Docker or Podman), with the
# optional files named by COMPOSE_FILE in .env. Everyday use, from this directory:
#
#     ./compose.sh ps                 # status
#     ./compose.sh logs -f app        # application log
#     ./compose.sh up -d              # apply changes to .env
#     ./compose.sh down               # stop (never "down -v": it deletes the database and the license)
#
# The same as "docker compose ..." with Docker. podman-compose does not read COMPOSE_FILE from .env, which
# this does for it. The engine is chosen as in install.sh: --engine docker|podman, CONTAINER_ENGINE, or
# Docker if its daemon answers, else Podman (see lib/engine.sh).
set -euo pipefail

cd "$(dirname "$0")"

die() { printf 'compose.sh: %s\n' "$*" >&2; exit 1; }

# shellcheck source=lib/engine.sh
. lib/engine.sh
engine_args "$@"
engine_select >&2
compose ${ARGS[@]+"${ARGS[@]}"}
