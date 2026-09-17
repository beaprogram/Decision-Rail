#!/usr/bin/env bash
# Points the DuckDNS name at this host's current public address. Run from cron every few minutes;
# an Always Free instance keeps its public IP across restarts, but a recreated instance does not.
#
# The DuckDNS token is a secret and lives in deploy/duckdns.token (owner-only, git-ignored), never in
# this file, the environment file or a command line where it would be visible in the process list.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

duckdns_update_usage() {
  cat <<'USAGE'
Usage: deploy/bin/duckdns-update.sh

Updates the DuckDNS record for DEMO_HOST (from deploy/public.env) to this host's public IPv4
address, using the token in deploy/duckdns.token. Takes no arguments. Intended for cron:
  */5 * * * * /opt/decisionrail/deploy/bin/duckdns-update.sh >> /var/log/duckdns.log 2>&1
USAGE
}
reject_arguments duckdns_update "$@"
require_dependencies curl
require_env_file
token_file="$deploy_dir/duckdns.token"
[[ -f "$token_file" ]] || { printf 'No token at %s. Create it with your DuckDNS token, mode 600.\n' "$token_file" >&2; exit 1; }
token=$(tr -d '[:space:]' < "$token_file")
subdomain=${DEMO_HOST%%.duckdns.org}
[[ "$subdomain" != "$DEMO_HOST" ]] || { printf 'DEMO_HOST %s is not a duckdns.org name; nothing to update.\n' "$DEMO_HOST" >&2; exit 1; }
# DuckDNS detects the caller's address when ip= is empty.
result=$(curl --silent --show-error --max-time 15 "https://www.duckdns.org/update?domains=$subdomain&token=$token&ip=")
printf '%s %s -> %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DEMO_HOST" "$result"
[[ "$result" == "OK" ]]
