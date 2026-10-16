#!/usr/bin/env bash
# Builds the customer release bundle for ReadyRoom.
#
#     scripts/release-bundle.sh <version> <image-ref>...
#     scripts/release-bundle.sh 1.2.3 ghcr.io/winllc/in-n-out-work:1.2.3
#
# The first image ref is the ReadyRoom image the bundled docker-compose.yml runs; it (and any others
# given) must already be in the local Docker image store - CI passes the image it has just
# smoke-tested, retagged. The third-party images the compose file names (postgres:16) are added
# automatically, pulled if they are not present.
#
# Output, in $OUT_DIR (default: dist/release under the repo root):
#   readyroom-<version>.tar.gz     unpacks to readyroom-<version>/ - see deploy/bundle/README.md
#   sbom-<image>.spdx.json         one per image; made with syft when not already there (CI puts them
#                                  there first with anchore/sbom-action). SKIP_SBOM=1 skips making them.
#   SHA256SUMS                     over the tarball and the SBOMs
# Signing (SHA256SUMS.asc) and upload are CI steps; see "Releases" in the README.
set -euo pipefail

PRODUCT=readyroom

usage() {
    sed -n '2,4p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

die() { printf 'release-bundle.sh: %s\n' "$*" >&2; exit 1; }

[ "$#" -ge 2 ] || usage
VERSION="$1"
shift
APP_IMAGE="$1"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] \
    || die "version '$VERSION' is not X.Y.Z or X.Y.Z-suffix."
case "$APP_IMAGE" in
    *:"$VERSION") ;;
    *) printf 'release-bundle.sh: warning: %s is not tagged %s\n' "$APP_IMAGE" "$VERSION" >&2 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO_ROOT/deploy/bundle"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/dist/release}"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
NAME="$PRODUCT-$VERSION"
TARBALL="$NAME.tar.gz"

command -v docker > /dev/null 2>&1 || die "docker is required."

# --- images ------------------------------------------------------------------------------------------
images=("$@")
for ref in "${images[@]}"; do
    docker image inspect "$ref" > /dev/null 2>&1 \
        || die "image $ref is not in the local image store (build or retag it first)."
done
# Third-party images the compose file runs, pinned to the tag it names.
while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    if ! docker image inspect "$ref" > /dev/null 2>&1; then
        echo "Pulling $ref"
        docker pull "$ref"
    fi
    images+=("$ref")
done < <(sed -n 's/^[[:space:]]*image:[[:space:]]*\([^[:space:]#]*\).*/\1/p' "$SRC/docker-compose.yml" \
            | grep -v '__READYROOM_IMAGE__' | sort -u)

short_name() {
    local s="${1##*/}"
    s="${s%%@*}"
    printf '%s' "${s%%:*}"
}

# --- stage -------------------------------------------------------------------------------------------
STAGING="$(mktemp -d "${TMPDIR:-/tmp}/release-bundle.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT
B="$STAGING/$NAME"
mkdir -p "$B/license" "$B/images" "$B/windows-client/tasks" "$B/migrations"

escape_sed() { printf '%s' "$1" | sed 's/[&|\\]/\\&/g'; }
app_image_sed="$(escape_sed "$APP_IMAGE")"
version_sed="$(escape_sed "$VERSION")"

sed "s|__READYROOM_IMAGE__|$app_image_sed|g" "$SRC/docker-compose.yml" > "$B/docker-compose.yml"
sed "s|__VERSION__|$version_sed|g" "$SRC/README.md" > "$B/README.md"
cp "$SRC/docker-compose.tls.yml" "$SRC/docker-compose.windows-auth.yml" "$SRC/env.template" "$B/"
cp "$SRC/install.sh" "$SRC/upgrade.sh" "$B/"
chmod 0755 "$B/install.sh" "$B/upgrade.sh"
cp "$SRC/license/README.txt" "$B/license/"
printf '%s\n' "$VERSION" > "$B/VERSION"

# The Windows client: the installer, the script it installs, its sample config and the task definitions.
cp "$REPO_ROOT/powershell/Install-InOutWorker.ps1" "$REPO_ROOT/powershell/Update-WorkStatus.ps1" \
   "$REPO_ROOT/powershell/inoutworker.config.sample.json" "$B/windows-client/"
cp "$REPO_ROOT/powershell/tasks/"*.xml "$B/windows-client/tasks/"
cp "$REPO_ROOT/db/migrations/"*.sql "$B/migrations/"

if grep -q '__[A-Z_]*__' "$B/docker-compose.yml" "$B/README.md"; then
    die "an unreplaced placeholder is left in the staged files."
fi
# The license lives in the ens-license volume; the old read-only ./license mount must not come back.
grep -q 'ens-license:/var/lib/ens/readyroom' "$B/docker-compose.yml" \
    || die "the bundled docker-compose.yml does not keep the license in the ens-license volume."
if grep -q '/etc/ens/license' "$B/docker-compose.yml"; then
    die "the bundled docker-compose.yml still mounts the old /etc/ens/license directory."
fi

echo "Saving images: ${images[*]}"
docker save -o "$B/images/$PRODUCT-images-$VERSION.tar" "${images[@]}"

# --- check nothing private is in it ------------------------------------------------------------------
forbidden="$(cd "$B" && find . \( -name '*.lic' -o -name '*.pem' -o -name '*.key' -o -name '*.keytab' \
    -o -name '*.p12' -o -name '*.jks' -o -name '.env' -o -name 'id_*' \) -print)"
[ -z "$forbidden" ] || die "refusing to bundle private material: $forbidden"

# --- tarball -----------------------------------------------------------------------------------------
rm -f "$OUT_DIR/$TARBALL" "$OUT_DIR/SHA256SUMS" "$OUT_DIR/SHA256SUMS.asc"
tar --sort=name --owner=0 --group=0 --numeric-owner -C "$STAGING" -czf "$OUT_DIR/$TARBALL" "$NAME"

# --- SBOMs -------------------------------------------------------------------------------------------
sboms=()
for ref in "${images[@]}"; do
    f="sbom-$(short_name "$ref").spdx.json"
    if [ ! -s "$OUT_DIR/$f" ] && [ "${SKIP_SBOM:-0}" != 1 ]; then
        if command -v syft > /dev/null 2>&1; then
            echo "Generating $f with syft"
            syft "docker:$ref" -o "spdx-json=$OUT_DIR/$f" -q
        else
            echo "warning: no $f and syft is not installed; the bundle ships without it." >&2
        fi
    fi
    [ -s "$OUT_DIR/$f" ] && sboms+=("$f")
done

# --- checksums ---------------------------------------------------------------------------------------
(cd "$OUT_DIR" && sha256sum "$TARBALL" "${sboms[@]}" > SHA256SUMS)

echo
echo "Release bundle for $PRODUCT $VERSION in $OUT_DIR:"
(cd "$OUT_DIR" && ls -l "$TARBALL" "${sboms[@]}" SHA256SUMS)
