#!/bin/sh
# Starts the image, waits for the app's own healthcheck inside the container, and removes it.
set -eu
image=$1
container=$(docker run --detach --publish-all "$image")
trap 'docker rm --force "$container" >/dev/null' EXIT
app=$(docker inspect --format '{{index .Config.Entrypoint 0}}' "$container")
for _ in $(seq 60); do
  if docker exec "$container" "$app" healthcheck >/dev/null 2>&1; then
    echo "$image is healthy in container ${container%"${container#????????????}"}"
    exit 0
  fi
  sleep 1
done
docker logs "$container" | tail -n 30 >&2
echo "$image did not turn healthy within 60s" >&2
exit 1
