#!/bin/sh
set -eu

if [ "$(id -u)" = 0 ]; then
    mkdir -p "$NETWORKHELM_GATEWAY_HOME"
    chown -R --no-dereference networkhelm:networkhelm "$NETWORKHELM_GATEWAY_HOME"
    chmod 700 "$NETWORKHELM_GATEWAY_HOME"
    exec setpriv --reuid=networkhelm --regid=networkhelm --init-groups "$@"
fi

if [ ! -w "$NETWORKHELM_GATEWAY_HOME" ]; then
    echo "The gateway cannot write to $NETWORKHELM_GATEWAY_HOME. Give the container user ownership of this directory." >&2
    exit 1
fi

exec "$@"
