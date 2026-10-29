#!/usr/bin/env bash
# Installs an RPM built by the java-app or node-app plugin the way an operator would, and proves it:
#
#  1. in a clean Rocky Linux 9 container: dnf accepts it, the files, %config and account are where
#     the packaging standard says, the app runs as its account and turns healthy, removal is clean;
#  2. under a real systemd (UBI 9 init image): the packaged, sandboxed unit starts the app, the app
#     turns healthy, and a stop counts as a clean exit. Prints the unit's systemd-analyze score.
#
# Usage: verify-rpm.sh PACKAGE.rpm   (on a Linux host with Docker, matching the RPM's architecture)
set -euo pipefail
rpm_file=$(cd "$(dirname "$1")" && pwd -P)/$(basename "$1")
name=$(basename "$rpm_file" | sed -E 's/-[^-]+-[^-]+\.[^.]+\.rpm$//')
port=18080

echo "== install, run and remove in Rocky Linux 9"
docker run --rm -v "$rpm_file:/tmp/app.rpm:ro" -e NAME="$name" -e PORT=$port rockylinux/rockylinux:9 bash -euo pipefail -c '
  dnf install -y -q /tmp/app.rpm
  rpm -V "$NAME"
  id "$NAME"
  test "$(stat -c %U:%a /var/lib/$NAME)" = "$NAME:750"
  test "$(stat -c %U:%G:%a /etc/$NAME)" = "root:$NAME:750"
  # Default configuration only when the app ships some (configDefaults), and then all in /etc/<app>.
  ! rpm -qc "$NAME" | grep -v "^/etc/$NAME/"
  export APP_ROOT=/var/lib/$NAME APP_CONF_DIR=/etc/$NAME APP_LOG_DIR=/var/log/$NAME APP_PORT=$PORT APP_LOG_TARGET=stdout
  runuser -u "$NAME" -- /opt/$NAME/bin/$NAME run & pid=$!
  for _ in $(seq 60); do runuser -u "$NAME" -- /opt/$NAME/bin/$NAME healthcheck && healthy=1 && break; sleep 1; done
  kill $pid
  test "${healthy:-}" = 1
  dnf remove -y -q "$NAME"
  test ! -e /opt/$NAME
'

echo "== run as a systemd service"
box=$(docker run -d --privileged --cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw \
  -v "$rpm_file:/tmp/app.rpm:ro" registry.access.redhat.com/ubi9/ubi-init)
trap 'docker rm -f "$box" >/dev/null' EXIT
docker exec -e NAME="$name" -e PORT=$port "$box" bash -euo pipefail -c '
  for _ in $(seq 30); do systemctl is-system-running --wait >/dev/null 2>&1 && break; sleep 1; done
  dnf install -y -q /tmp/app.rpm
  echo "APP_PORT=$PORT" > /etc/$NAME/env
  systemctl start "$NAME"
  for _ in $(seq 60); do
    APP_PORT=$PORT runuser -u "$NAME" -- /opt/$NAME/bin/$NAME healthcheck && healthy=1 && break
    sleep 1
  done
  journalctl -u "$NAME" --no-pager | tail -n 20
  test "${healthy:-}" = 1
  systemd-analyze security "$NAME" --no-pager | tail -n 1
  systemctl stop "$NAME"
  test "$(systemctl show -p Result --value "$NAME")" = success
'
echo "verified $name"
