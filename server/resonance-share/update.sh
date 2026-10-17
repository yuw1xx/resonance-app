#!/bin/sh
# Updates the server to the newest image for the version in .env (SERVER_VERSION, default 1)
# and restarts it only if something changed. Safe to run from cron or a systemd timer.
set -eu
cd "$(dirname "$0")"
if ! docker compose pull --quiet; then
    echo "Couldn't download the server image (no connection, or no release published yet)." >&2
    echo "The running server was left as it is." >&2
    exit 1
fi
docker compose up -d --remove-orphans
docker image prune -f >/dev/null
