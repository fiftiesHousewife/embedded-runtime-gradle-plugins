#!/usr/bin/env bash
# Installs an app archive from the java-app or node-app plugin for an account without root. With
# root, install the RPM instead (rpm { enabled = true }): dnf then does all of this.
#
#   $APP_ROOT/releases/<version>/   one extracted archive per version
#   $APP_ROOT/current -> releases/<version>
#   $APP_ROOT/conf/                 seeded from conf.defaults/ once, never overwritten; conf/env holds KEY=VALUE
#   $APP_ROOT/data/ logs/ run/
#
# Usage: install.sh install --archive FILE|URL [--sha256 HEX] --app-root DIR [--health-timeout SEC] [--keep N]
#        install.sh rollback|status|stop --app-root DIR
#
# A URL is fetched with curl; its checksum from URL.sha256 (published next to every archive).
# Credentials come from ~/.netrc. The app runs as a systemd user service where one is available,
# otherwise under nohup (--supervisor systemd|nohup to choose). A release that fails its
# healthcheck is replaced by the previous one again.
set -euo pipefail

die() { echo "install.sh: $*" >&2; exit 1; }
log() { echo "==> $*"; }

COMMAND=${1:-}; [ $# -gt 0 ] && shift
ARCHIVE="" SHA="" APP_ROOT="" SUPERVISOR="" HEALTH_TIMEOUT=60 KEEP=3
while [ $# -gt 0 ]; do
  case "$1" in
    --archive) ARCHIVE=$2 ;;
    --sha256) SHA=$2 ;;
    --app-root) APP_ROOT=$2 ;;
    --supervisor) SUPERVISOR=$2 ;;
    --health-timeout) HEALTH_TIMEOUT=$2 ;;
    --keep) KEEP=$2 ;;
    *) die "unknown option $1 (see the top of this script)" ;;
  esac
  shift 2
done
[ -n "$APP_ROOT" ] || die "--app-root is required"
mkdir -p "$APP_ROOT/releases" "$APP_ROOT/conf" "$APP_ROOT/data" "$APP_ROOT/logs" "$APP_ROOT/run"
APP_ROOT=$(cd "$APP_ROOT" && pwd -P)
export APP_ROOT
umask 027
if [ -z "$SUPERVISOR" ]; then
  if systemctl --user show-environment >/dev/null 2>&1; then SUPERVISOR=systemd; else SUPERVISOR=nohup; fi
fi

field() { sed -n "s/^ *\"$2\" *: *\"\\([^\"]*\\)\".*/\\1/p" "$1/META-INF/app.json" | head -n 1; }
current() { [ -L "$APP_ROOT/current" ] && basename "$(readlink "$APP_ROOT/current")" || true; }
previous() { cat "$APP_ROOT/run/previous" 2>/dev/null || true; }
name() { field "$APP_ROOT/current" name; }
launcher() { echo "$APP_ROOT/current/bin/$(name)"; }
load_env() { if [ -f "$APP_ROOT/conf/env" ]; then set -a; . "$APP_ROOT/conf/env"; set +a; fi; }
with_env() { ( load_env; "$@" ); }
healthy() { with_env "$(launcher)" healthcheck >/dev/null 2>&1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -c1-64; }
host() {
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) echo linux-x64 ;; Linux-aarch64) echo linux-aarch64 ;;
    Darwin-x86_64) echo mac-x64 ;; Darwin-arm64) echo mac-aarch64 ;; *) die "unsupported machine $(uname -sm)" ;;
  esac
}

start() {
  if [ "$SUPERVISOR" = systemd ]; then
    local unit=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/$(name).service
    mkdir -p "$(dirname "$unit")"
    cat > "$unit" <<UNIT
[Unit]
Description=$(name)
[Service]
Environment=APP_ROOT=$APP_ROOT
EnvironmentFile=-$APP_ROOT/conf/env
WorkingDirectory=$APP_ROOT
ExecStart=$APP_ROOT/current/bin/$(name) run
Restart=on-failure
NoNewPrivileges=true
[Install]
WantedBy=default.target
UNIT
    systemctl --user daemon-reload && systemctl --user enable "$(name)" && systemctl --user restart "$(name)"
  else
    # exec keeps one PID from the subshell to the runtime, so $! is the app itself.
    (cd "$APP_ROOT" && load_env && exec nohup "$(launcher)" run >> "$APP_ROOT/logs/console.log" 2>&1 < /dev/null) &
    echo $! > "$APP_ROOT/run/app.pid"
  fi
}

stop() {
  [ -L "$APP_ROOT/current" ] || return 0
  if [ "$SUPERVISOR" = systemd ]; then systemctl --user stop "$(name)" 2>/dev/null || true; return; fi
  local pid; pid=$(cat "$APP_ROOT/run/app.pid" 2>/dev/null) || return 0
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$APP_ROOT/run/app.pid"
}

# Starts `current` and waits for its healthcheck.
start_healthy() {
  start
  for _ in $(seq "$HEALTH_TIMEOUT"); do healthy && return 0; sleep 1; done
  return 1
}

# Atomic with GNU mv -T (Linux); elsewhere ln -sfn, which replaces the link in two steps.
switch_to() {
  ln -sfn "releases/$1" "$APP_ROOT/current.new"
  mv -fT "$APP_ROOT/current.new" "$APP_ROOT/current" 2>/dev/null ||
    { rm -f "$APP_ROOT/current.new"; ln -sfn "releases/$1" "$APP_ROOT/current"; }
}

install() {
  [ -n "$ARCHIVE" ] || die "install needs --archive"
  local file top version
  work=$(mktemp -d "$APP_ROOT/.install.XXXXXX") # global: the EXIT trap runs after this function returns
  trap 'rm -rf "${work:-}"' EXIT
  file=$work/$(basename "$ARCHIVE")
  case "$ARCHIVE" in
    http://*|https://*)
      curl -fsSL --netrc-optional -o "$file" "$ARCHIVE"
      [ -n "$SHA" ] || SHA=$(curl -fsSL --netrc-optional "$ARCHIVE.sha256" | cut -c1-64) ;;
    *) cp "$ARCHIVE" "$file" ;;
  esac
  if [ -n "$SHA" ]; then [ "$(sha256 "$file")" = "$SHA" ] || die "SHA-256 mismatch for $(basename "$file")"
  else echo "install.sh: WARNING: no checksum to verify $(basename "$file") against" >&2; fi

  mkdir "$work/x"
  case "$file" in *.zip) unzip -q "$file" -d "$work/x" ;; *) tar -xf "$file" -C "$work/x" ;; esac
  top=$(echo "$work"/x/*)
  [ -f "$top/META-INF/app.json" ] || die "not an app archive: no single <app>-<version>/META-INF/app.json"
  [ "$(field "$top" target)" = "$(host)" ] || die "archive is for $(field "$top" target), this machine is $(host)"
  version=$(field "$top" version)
  [ ! -e "$APP_ROOT/releases/$version" ] || [ "$(current)" != "$version" ] || die "$version is already current"
  rm -rf "${APP_ROOT:?}/releases/$version" && mv "$top" "$APP_ROOT/releases/$version"
  (cd "$APP_ROOT/releases/$version/conf.defaults" 2>/dev/null && find . -type f) | while read -r f; do
    [ -e "$APP_ROOT/conf/$f" ] || { mkdir -p "$(dirname "$APP_ROOT/conf/$f")"; cp "$APP_ROOT/releases/$version/conf.defaults/$f" "$APP_ROOT/conf/$f"; }
  done

  local was; was=$(current)
  stop; switch_to "$version"
  if start_healthy; then
    log "$(name) $version is healthy"
    [ -z "$was" ] || [ "$was" = "$version" ] || echo "$was" > "$APP_ROOT/run/previous"
    # Keep the newest $KEEP releases, and always current and previous. (awk, not BSD-incompatible head -n -N.)
    ls -1t "$APP_ROOT/releases" | awk -v keep="$KEEP" 'NR > keep' | while read -r r; do
      [ "$r" = "$(current)" ] || [ "$r" = "$(previous)" ] || rm -rf "${APP_ROOT:?}/releases/$r"
    done
  else
    stop
    [ -z "$was" ] || { switch_to "$was"; start_healthy || true; }
    tail -n 30 "$APP_ROOT/logs/console.log" >&2 2>/dev/null || true
    die "$version failed its healthcheck within ${HEALTH_TIMEOUT}s${was:+; back on $was}"
  fi
}

case "$COMMAND" in
  install) install ;;
  rollback)
    to=$(previous); from=$(current)
    [ -n "$to" ] && [ -d "$APP_ROOT/releases/$to" ] || die "no previous release recorded"
    stop; switch_to "$to"; echo "$from" > "$APP_ROOT/run/previous"
    start_healthy || die "$to failed its healthcheck after rollback"
    log "rolled back $from -> $to" ;;
  status)
    [ -L "$APP_ROOT/current" ] || die "nothing installed in $APP_ROOT"
    echo "app:      $(name)"; echo "current:  $(current)"; echo "previous: $(previous)"
    if healthy; then echo "health:   healthy"; else echo "health:   unhealthy"; fi ;;
  stop) stop ;;
  *) sed -n '2,16p' "$0"; exit 64 ;;
esac
