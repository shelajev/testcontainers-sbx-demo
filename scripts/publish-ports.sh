#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "${DOCKER_HOST:-}" ]]; then
  echo "Usage: DOCKER_HOST=unix:///path/to/bridge.sock $0 SANDBOX_NAME" >&2
  exit 2
fi

sandbox=$1

publish_container() {
  local container_id=$1 ports port result
  # A short-lived container may be removed before its start event is handled.
  ports=$(docker inspect -f '{{range .NetworkSettings.Ports}}{{range .}}{{println .HostPort}}{{end}}{{end}}' "$container_id" 2>/dev/null) || return 0
  while IFS= read -r port; do
    [[ -n "$port" ]] || continue
    if result=$(sbx ports "$sandbox" --publish "$port:$port" 2>&1); then
      printf '%s\n' "$result"
    elif [[ "$result" != *"already published"* ]]; then
      printf 'Could not publish container %s port %s: %s\n' "$container_id" "$port" "$result" >&2
    fi
  done < <(printf '%s\n' "$ports" | sort -u)
}

while true; do
  # Replay events since before the scan so a start during the scan is not missed.
  since=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  while IFS= read -r container_id; do
    publish_container "$container_id"
  done < <(docker ps -q)

  if docker events --since "$since" --filter type=container --filter event=start --format '{{.Actor.ID}}' |
    while IFS= read -r container_id; do
      publish_container "$container_id"
    done; then
    echo 'Docker event stream ended; reconnecting' >&2
  else
    echo 'Docker event stream disconnected; reconnecting' >&2
  fi
  sleep 1
done
