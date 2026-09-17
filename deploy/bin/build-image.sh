#!/usr/bin/env bash
# Builds the application image locally, stamped the way the release workflow stamps it, so a
# rehearsal on this machine reports a real commit from GET /actuator/info. The published image for
# the demo host comes from .github/workflows/release.yml, not from here.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

build_image_usage() {
  cat <<'USAGE'
Usage: deploy/bin/build-image.sh

Builds ghcr.io/beaprogram/decision-rail:sha-<HEAD commit> from the working tree for this machine's
architecture and prints the tag. Takes no arguments. Refuses a dirty working tree, because the tag
names a commit and the image must be what that commit builds.
USAGE
}
reject_arguments build_image "$@"
require_dependencies docker git

cd "$project_root"
if [[ -n "$(git status --porcelain)" ]]; then
  printf 'Refusing: the working tree has uncommitted changes, so the image would not be what its tag names.\n' >&2
  exit 1
fi
commit=$(git rev-parse HEAD)
image="ghcr.io/beaprogram/decision-rail:sha-$commit"
docker build \
  --build-arg "APP_COMMIT=$commit" \
  --build-arg "APP_IMAGE=$image" \
  --build-arg "APP_BUILT_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --tag "$image" .
printf '%s\n' "$image"
