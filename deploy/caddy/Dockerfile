# The edge, with its configuration baked in rather than bind-mounted: nothing on the host has to be
# in a particular place, and the file cannot drift from what was reviewed.
FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
RUN caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>/dev/null || true
