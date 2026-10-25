#!/usr/bin/env bash
# Installs an RPM built by the java-app or node-app plugin into a clean Rocky Linux container the
# way an operator would, and proves it: dnf accepts it, the files and account are where the
# packaging standard says, the app starts as its account and turns healthy, and removal is clean.
#
# Usage: verify-rpm.sh PACKAGE.rpm   (run on a Linux host with Docker, matching the RPM's architecture)
set -euo pipefail
rpm_file=$(cd "$(dirname "$1")" && pwd -P)/$(basename "$1")
name=$(basename "$rpm_file" | sed -E 's/-[^-]+-[^-]+\.[^.]+\.rpm$//')

docker run --rm -v "$rpm_file:/tmp/app.rpm:ro" -e NAME="$name" rockylinux/rockylinux:9 bash -euo pipefail -c '
  dnf install -y -q /tmp/app.rpm
  rpm -qi "$NAME"
  rpm -V "$NAME"
  id "$NAME"
  test "$(stat -c %U:%a /var/lib/$NAME)" = "$NAME:750"
  test -f /usr/lib/systemd/system/$NAME.service
  rpm -qc "$NAME" | grep -q "^/etc/$NAME/"

  export APP_ROOT=/var/lib/$NAME APP_CONF_DIR=/etc/$NAME APP_DATA_DIR=/var/lib/$NAME APP_LOG_DIR=/var/log/$NAME
  export APP_PORT=18080 APP_LOG_TARGET=stdout
  runuser -u "$NAME" -- /opt/$NAME/bin/$NAME run & pid=$!
  for _ in $(seq 60); do
    if runuser -u "$NAME" -- /opt/$NAME/bin/$NAME healthcheck; then healthy=1; break; fi
    sleep 1
  done
  kill $pid
  test "${healthy:-}" = 1

  dnf remove -y -q "$NAME"
  test ! -e /opt/$NAME
  echo "verified $NAME: installed, healthy as $NAME, removed"
'
