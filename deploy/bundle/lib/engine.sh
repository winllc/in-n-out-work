# shellcheck shell=bash
# ReadyRoom - picks the container engine and its compose command for install.sh, upgrade.sh, backup.sh
# and restore.sh, which source this file. Not run on its own.
#
# Docker or Podman, in this order:
#   1. --engine docker|podman on the script's command line;
#   2. CONTAINER_ENGINE=docker|podman in the environment;
#   3. Docker, when it is installed and its daemon answers; otherwise Podman.
# The compose command is "docker compose" (or a v2 "docker-compose") for Docker, and "podman-compose"
# (or "podman compose") for Podman. COMPOSE_COMMAND overrides it, e.g. COMPOSE_COMMAND="podman compose".
#
# Afterwards the scripts (and compose.sh, for everyday commands) use only:
#   "$ENGINE" ...                the engine's own CLI (load, exec, run, inspect)
#   compose ...                  the compose command, in the bundle directory, with COMPOSE_FILE from .env
#   service_container NAME       the id of the running container of a compose service, or nothing
#   service_exec NAME CMD...     runs CMD in it, with stdin attached
#   volume_name NAME             the engine's name of a named volume of this project
# and never call docker or podman by name.

# The compose project name, fixed in docker-compose.yml (name: readyroom).
READYROOM_PROJECT=readyroom

ENGINE=""
COMPOSE=()
# The script's arguments without --engine, set by engine_args.
ARGS=()

# engine_args "$@" takes --engine X / --engine=X out of the arguments; the rest are left in ARGS.
engine_args() {
    ARGS=()
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --engine) [ "$#" -ge 2 ] || die "--engine needs docker or podman."; CONTAINER_ENGINE="$2"; shift 2 ;;
            --engine=*) CONTAINER_ENGINE="${1#--engine=}"; shift ;;
            *) ARGS+=("$1"); shift ;;
        esac
    done
}

# The "docker" command of the podman-docker package is Podman, not Docker.
docker_is_podman() {
    docker --version 2> /dev/null | grep -qi podman
}

# version_at_least 1.2.3 1.1 -> true when the first version is the same or newer.
version_at_least() {
    [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n 1)" = "$2" ]
}

engine_select() {
    local want="${CONTAINER_ENGINE:-}" v
    if [ -z "$want" ]; then
        if command -v docker > /dev/null 2>&1 && ! docker_is_podman && docker info > /dev/null 2>&1; then
            want=docker
        elif command -v podman > /dev/null 2>&1; then
            want=podman
        elif command -v docker > /dev/null 2>&1; then
            die "cannot reach the Docker daemon (is it running, and may this user use it?), and Podman is not installed."
        else
            die "neither Docker nor Podman is installed (or on PATH)."
        fi
    fi

    case "$want" in
        docker)
            command -v docker > /dev/null 2>&1 || die "docker is not installed or not on PATH."
            ! docker_is_podman || die "the docker command here is Podman's (podman-docker): use --engine podman."
            docker info > /dev/null 2>&1 || die "cannot reach the Docker daemon (is it running, and may this user use it?)."
            if [ -n "${COMPOSE_COMMAND:-}" ]; then
                read -r -a COMPOSE <<< "$COMPOSE_COMMAND"
            elif docker compose version > /dev/null 2>&1; then
                COMPOSE=(docker compose)
            elif command -v docker-compose > /dev/null 2>&1 \
                    && docker-compose version --short 2> /dev/null | grep -q '^v\{0,1\}[2-9]'; then
                COMPOSE=(docker-compose)
            else
                die "Docker Compose v2 is required: the compose plugin (docker compose) or a v2 docker-compose. docker-compose v1 cannot read this bundle's docker-compose.yml."
            fi
            ;;
        podman)
            command -v podman > /dev/null 2>&1 || die "podman is not installed or not on PATH."
            podman info > /dev/null 2>&1 || die "podman cannot run here (podman info failed)."
            v="$(podman version --format '{{.Client.Version}}' 2> /dev/null || true)"
            version_at_least "${v:-0}" 4.4 || die "Podman 4.4 or later is required (this is ${v:-unknown})."
            if [ -n "${COMPOSE_COMMAND:-}" ]; then
                read -r -a COMPOSE <<< "$COMPOSE_COMMAND"
            elif command -v podman-compose > /dev/null 2>&1; then
                # Called directly: "podman compose" prefers Docker's docker-compose when that is installed too,
                # and that needs the Podman API socket.
                v="$(podman-compose version 2> /dev/null | sed -n 's/^podman-compose version \([0-9.]*\).*/\1/p' | head -n 1)"
                version_at_least "${v:-0}" 1.3 \
                    || die "podman-compose 1.3 or later is required (this is ${v:-unknown}): pip install --user --upgrade podman-compose"
                COMPOSE=(podman-compose)
            elif podman compose version > /dev/null 2>&1; then
                COMPOSE=(podman compose)
            else
                die "podman-compose is required with Podman (1.3 or later: pip install --user podman-compose, or your distribution's package)."
            fi
            ;;
        *) die "CONTAINER_ENGINE (or --engine) must be docker or podman, not '$want'." ;;
    esac
    ENGINE="$want"

    local mode=""
    if [ "$ENGINE" = podman ]; then
        if [ "$(podman info --format '{{.Host.Security.Rootless}}' 2> /dev/null)" = true ]; then
            mode=" (rootless)"
        else
            mode=" (rootful)"
        fi
    fi
    printf 'Container engine: %s%s; compose command: %s\n' "$ENGINE" "$mode" "${COMPOSE[*]}"
}

# COMPOSE_FILE (the optional TLS and Windows sign-in files) is read from .env by Docker Compose but only
# from the environment by podman-compose, so it is passed in the environment for both. One set in the
# environment already wins, as it does with Docker Compose.
compose() {
    local files="${COMPOSE_FILE:-}"
    if [ -z "$files" ] && [ -f .env ]; then
        files="$(sed -n 's/^COMPOSE_FILE=//p' .env | tail -n 1)"
    fi
    if [ -n "$files" ]; then
        COMPOSE_FILE="$files" "${COMPOSE[@]}" "$@"
    else
        "${COMPOSE[@]}" "$@"
    fi
}

# Found by the compose labels both Docker Compose and podman-compose put on what they start, so it works
# whatever either one names the container.
service_container() {
    "$ENGINE" ps -q \
        --filter "label=com.docker.compose.project=$READYROOM_PROJECT" \
        --filter "label=com.docker.compose.service=$1" | head -n 1
}

service_exec() {
    local id
    id="$(service_container "$1")"
    [ -n "$id" ] || return 1
    shift
    "$ENGINE" exec -i "$id" "$@"
}

volume_name() {
    printf '%s_%s' "$READYROOM_PROJECT" "$1"
}
