#!/usr/bin/env bash
# Removes the public demo from this host entirely: containers, network, volumes and certificates.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

teardown_usage() {
  cat <<'USAGE'
Usage: deploy/bin/teardown.sh

Stops and removes the decisionrail-public stack including its volumes. Requires typing the project
name. Takes no arguments. The environment file and any backups under deploy/backups are left for
you to delete or keep.
USAGE
}
reject_arguments teardown "$@"
require_dependencies docker jq
require_env_file
assert_public_project
confirm_destruction "Teardown"

compose down --remove-orphans --volumes
printf 'Removed. deploy/public.env and deploy/backups/ are still on disk.\n'
