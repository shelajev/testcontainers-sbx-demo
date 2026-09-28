#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "${DOCKER_HOST:-}" ]]; then
  echo "Usage: DOCKER_HOST=unix:///path/to/bridge.sock $0 SANDBOX_NAME" >&2
  exit 2
fi

sandbox=$1

docker events --filter type=container --filter event=start --format '{{.Actor.ID}}' |
  while IFS= read -r container_id; do
    docker inspect -f '{{range .NetworkSettings.Ports}}{{range .}}{{println .HostPort}}{{end}}{{end}}' "$container_id" |
      sort -u |
      while IFS= read -r port; do
        if [[ -n "$port" ]]; then
          sbx ports "$sandbox" --publish "$port:$port"
        fi
      done
  done
