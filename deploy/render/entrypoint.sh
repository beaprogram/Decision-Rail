#!/bin/bash
# One Render container, two direct children. No credentials are printed or interpreted as shell code.
set -euo pipefail

usage() {
    printf '%s\n' 'Usage: entrypoint.sh [--help]' \
        'Starts the public Render edge and its loopback-only Java application.' \
        'Required: RENDER_EXTERNAL_HOSTNAME and RENDER_TRUSTED_PROXIES.'
}
if (( $# )); then
    if (( $# == 1 )) && [[ $1 == --help ]]; then usage; exit 0; fi
    usage >&2
    exit 2
fi
fail() { printf 'Render startup: %s\n' "$1" >&2; exit 2; }

export PORT=${PORT:-10000}
[[ $PORT =~ ^[1-9][0-9]{3,4}$ ]] || fail 'PORT must be an unprivileged decimal port.'
(( PORT >= 1024 && PORT <= 65535 )) || fail 'PORT is outside the allowed range.'
case "$PORT" in 8080|18012|18013|19099) fail 'PORT conflicts with the application or a Render reserved port.' ;; esac

# The value is substituted into Caddy's configuration: accept a DNS name only, never configuration
# syntax, a URL, whitespace or a port. Forwarding always uses this canonical public hostname.
hostname=${RENDER_EXTERNAL_HOSTNAME:-}
[[ -n $hostname && ${#hostname} -le 253 && $hostname != *..* ]] || fail 'Set a valid RENDER_EXTERNAL_HOSTNAME.'
[[ $hostname =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ ]] || fail 'RENDER_EXTERNAL_HOSTNAME must be a DNS name.'
IFS=. read -r -a hostname_labels <<< "$hostname"
for label in "${hostname_labels[@]}"; do
    [[ ${#label} -le 63 && $label != -* && $label != *- ]] || fail 'RENDER_EXTERNAL_HOSTNAME has an invalid label.'
done
export RENDER_EXTERNAL_HOSTNAME=$hostname

# Do not guess Render's internal ranges. Operators may explicitly select Caddy's private_ranges
# trust zone when that topology is established, or supply space-separated IP addresses/CIDRs.
# Caddy performs semantic IP/CIDR validation; this validation also prevents config injection.
proxies=${RENDER_TRUSTED_PROXIES:-}
[[ -n $proxies && $proxies != *$'\n'* && $proxies != *$'\r'* && $proxies != *$'\t'* ]] || fail 'Set RENDER_TRUSTED_PROXIES explicitly.'
IFS=' ' read -r -a proxy_ranges <<< "$proxies"
(( ${#proxy_ranges[@]} > 0 )) || fail 'Set RENDER_TRUSTED_PROXIES explicitly.'
for proxy in "${proxy_ranges[@]}"; do
    [[ $proxy == private_ranges || $proxy =~ ^[0-9a-fA-F:.]+(/[0-9]{1,3})?$ ]] || fail 'Invalid trusted proxy address or CIDR.'
    [[ ! $proxy =~ /0+$ ]] || fail 'Trusting every network is not permitted.'
done
export RENDER_TRUSTED_PROXIES=$proxies

shutdown_seconds=${RENDER_SHUTDOWN_SECONDS:-20}
[[ $shutdown_seconds =~ ^[1-9][0-9]?$ ]] && (( shutdown_seconds <= 25 )) || fail 'RENDER_SHUTDOWN_SECONDS must be 1 through 25.'

# Validate before starting Java; an invalid edge must never leave the application running alone.
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

java_pid=
caddy_pid=
stop_children() {
    trap - EXIT
    trap '' TERM INT
    local pid deadline
    for pid in "$java_pid" "$caddy_pid"; do
        [[ -z $pid ]] || kill -TERM "$pid" 2>/dev/null || true
    done
    deadline=$((SECONDS + shutdown_seconds))
    while { [[ -n $java_pid ]] && kill -0 "$java_pid" 2>/dev/null; } || \
          { [[ -n $caddy_pid ]] && kill -0 "$caddy_pid" 2>/dev/null; }; do
        (( SECONDS < deadline )) || break
        sleep 0.1
    done
    for pid in "$java_pid" "$caddy_pid"; do
        if [[ -n $pid ]]; then
            kill -KILL "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
}
trap stop_children EXIT
# A signal can arrive after fork but before $! is assigned. Record it until both child IDs are
# captured, otherwise EXIT cleanup could miss the just-started process and skip graceful shutdown.
stop_requested=0
trap 'stop_requested=143' TERM
trap 'stop_requested=130' INT

# These are deployment invariants, not environment-overridable defaults. The render profile's
# connection/pool settings remain overridable through ordinary Spring environment properties for a
# disposable rehearsal. The four public safety settings and the network boundary stay fixed.
java -jar /app/app.jar \
    --spring.profiles.active=render \
    --server.address=127.0.0.1 \
    --server.port=8080 \
    --server.forward-headers-strategy=native \
    '--server.tomcat.remoteip.internal-proxies=127\.0\.0\.1' \
    --server.tomcat.remoteip.remote-ip-header=X-Forwarded-For \
    --server.tomcat.remoteip.protocol-header=X-Forwarded-Proto \
    --server.servlet.session.cookie.secure=true \
    --app.ui.secure-cookies=true \
    --app.public-demo.enabled=true \
    --app.demo-enabled=false \
    --app.events.fault-injection-enabled=false &
java_pid=$!
caddy run --config /etc/caddy/Caddyfile --adapter caddyfile &
caddy_pid=$!
# Both IDs are now known. Install immediate-exit handlers before checking the recorded signal, so a
# signal arriving during this handover is either recorded and checked or handled by EXIT cleanup.
trap 'exit 143' TERM
trap 'exit 130' INT
if (( stop_requested != 0 )); then exit "$stop_requested"; fi

# Portable to Bash 3 as well as the container's Bash 5. The shell reaps asynchronous children; their
# recorded exit status remains available to wait. Detection is bounded to the polling interval.
while kill -0 "$java_pid" 2>/dev/null && kill -0 "$caddy_pid" 2>/dev/null; do
    sleep 0.2
done
status=0
if ! kill -0 "$java_pid" 2>/dev/null; then
    wait "$java_pid" || status=$?
else
    wait "$caddy_pid" || status=$?
fi
# Even an unrequested clean exit has stopped the service; report failure so it can be restarted.
(( status != 0 )) || status=1
exit "$status"
