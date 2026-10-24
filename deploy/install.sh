#!/usr/bin/env bash
# Platform bundle installer — no root required.
#
# Installs a bundle produced by io.github.fiftieshousewife.platform (Java or Node, the layout is identical) into
# an application root owned by the invoking account:
#
#   $APP_ROOT/
#     releases/<version>/   one extracted bundle per version
#     current -> releases/<version>
#     conf/                 seeded from conf.defaults/ once; never overwritten (conf/env: KEY=VALUE)
#     data/  logs/  run/
#
# Usage:
#   install.sh install  --coordinates GROUP:NAME:VERSION --app-root DIR [options]
#   install.sh install  --archive FILE.tar.gz [--sha256 HEX] --app-root DIR [options]
#   install.sh rollback --app-root DIR       switch back to the previous release
#   install.sh status   --app-root DIR
#   install.sh start | stop | restart --app-root DIR
#
# Options:
#   --repo-url URL          Maven repository holding the bundle
#                           (default $INSTALL_REPO_URL, e.g. https://artifactory.example.com/artifactory/libs-release-local)
#   --platform ID           bundle classifier (default: this machine, e.g. linux-x64)
#   --supervisor MODE       auto (default) | systemd (user unit) | nohup | none
#   --health-timeout SEC    seconds to wait for `bin/<app> healthcheck` (default 60)
#   --keep N                releases to keep, including current (default 3)
#   --force                 reinstall a version that is already present
#
# Credentials: ARTIFACTORY_USER and ARTIFACTORY_TOKEN, passed to curl on stdin (never on the
# command line, so they do not appear in `ps`), or a ~/.netrc entry. INSTALL_CA_FILE adds a CA
# bundle for a corporate TLS certificate.
#
# On a failed healthcheck the previous release is restored and restarted automatically.
set -euo pipefail

die() { echo "install.sh: $*" >&2; exit 1; }
log() { echo "==> $*"; }

COMMAND=${1:-}; [ $# -gt 0 ] && shift
COORDS="" ARCHIVE="" SHA="" APP_ROOT="" REPO_URL=${INSTALL_REPO_URL:-} PLATFORM=""
SUPERVISOR=auto HEALTH_TIMEOUT=60 KEEP=3 FORCE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --coordinates) COORDS=$2; shift 2 ;;
    --archive) ARCHIVE=$2; shift 2 ;;
    --sha256) SHA=$2; shift 2 ;;
    --app-root) APP_ROOT=$2; shift 2 ;;
    --repo-url) REPO_URL=$2; shift 2 ;;
    --platform) PLATFORM=$2; shift 2 ;;
    --supervisor) SUPERVISOR=$2; shift 2 ;;
    --health-timeout) HEALTH_TIMEOUT=$2; shift 2 ;;
    --keep) KEEP=$2; shift 2 ;;
    --force) FORCE=true; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
[ -n "$APP_ROOT" ] || die "--app-root is required"
mkdir -p "$APP_ROOT"
APP_ROOT=$(cd "$APP_ROOT" && pwd -P)
umask 027

host_platform() {
  local os arch
  case "$(uname -s)" in Linux) os=linux ;; Darwin) os=mac ;; *) die "unsupported OS $(uname -s)" ;; esac
  case "$(uname -m)" in x86_64|amd64) arch=x64 ;; aarch64|arm64) arch=aarch64 ;; *) die "unsupported arch $(uname -m)" ;; esac
  echo "$os-$arch"
}

# Reads a top-level string field from META-INF/bundle.json without needing jq.
manifest_field() {
  sed -n "s/^ *\"$2\" *: *\"\\([^\"]*\\)\".*/\\1/p" "$1/META-INF/bundle.json" | head -n 1
}

current_release() { [ -L "$APP_ROOT/current" ] && basename "$(readlink "$APP_ROOT/current")" || true; }
app_name() { local c; c=$(current_release); [ -n "$c" ] && manifest_field "$APP_ROOT/releases/$c" name || true; }

load_env() {
  # conf/env holds per-server settings such as APP_PORT; exported for healthchecks and nohup starts.
  if [ -f "$APP_ROOT/conf/env" ]; then set -a; . "$APP_ROOT/conf/env"; set +a; fi
  export APP_ROOT
}

curl_get() { # url output
  local args=(--fail --silent --show-error --location --retry 3 --output "$2")
  [ -n "${INSTALL_CA_FILE:-}" ] && args+=(--cacert "$INSTALL_CA_FILE")
  if [ -n "${ARTIFACTORY_USER:-}" ] && [ -n "${ARTIFACTORY_TOKEN:-}" ]; then
    printf 'user = "%s:%s"\n' "$ARTIFACTORY_USER" "$ARTIFACTORY_TOKEN" | curl "${args[@]}" --config - "$1"
  else
    curl "${args[@]}" --netrc-optional "$1"
  fi
}

sha256_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# ---- supervision -------------------------------------------------------------------------------

resolve_supervisor() {
  if [ "$SUPERVISOR" = auto ]; then
    if command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
      SUPERVISOR=systemd
    else
      SUPERVISOR=nohup
    fi
  fi
}

unit_name() { echo "$1.service"; }

write_unit() { # name
  local dir=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
  mkdir -p "$dir"
  cat > "$dir/$(unit_name "$1")" <<EOF
# Managed by install.sh — regenerated on every install.
[Unit]
Description=$1 (platform bundle)
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$APP_ROOT
Environment=APP_ROOT=$APP_ROOT
EnvironmentFile=-$APP_ROOT/conf/env
ExecStart=$APP_ROOT/current/bin/$1 run
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
UMask=0027

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable "$(unit_name "$1")" >/dev/null 2>&1 || true
  if command -v loginctl >/dev/null && [ "$(loginctl show-user "$(id -un)" -p Linger --value 2>/dev/null)" != yes ]; then
    echo "WARNING: lingering is off for $(id -un): the service stops at logout and does not start at boot." >&2
    echo "         Ask your platform team to run: loginctl enable-linger $(id -un)" >&2
  fi
}

pid_file() { echo "$APP_ROOT/run/$1.pid"; }

is_running() { # name
  case "$SUPERVISOR" in
    systemd) systemctl --user is-active --quiet "$(unit_name "$1")" ;;
    nohup) local p; p=$(pid_file "$1"); [ -f "$p" ] && kill -0 "$(cat "$p")" 2>/dev/null ;;
    none) return 1 ;;
  esac
}

start_app() { # name
  case "$SUPERVISOR" in
    systemd) write_unit "$1"; systemctl --user restart "$(unit_name "$1")" ;;
    nohup)
      mkdir -p "$APP_ROOT/run" "$APP_ROOT/logs"
      load_env
      # exec chain (subshell -> nohup -> launcher -> runtime) keeps one PID, so $! is the app itself.
      (cd "$APP_ROOT" && exec nohup "$APP_ROOT/current/bin/$1" run >> "$APP_ROOT/logs/console.log" 2>&1 < /dev/null) &
      echo $! > "$(pid_file "$1")"
      disown 2>/dev/null || true
      ;;
    none) log "supervisor=none: start $APP_ROOT/current/bin/$1 run yourself" ;;
  esac
}

stop_app() { # name
  [ -n "$1" ] || return 0
  case "$SUPERVISOR" in
    systemd) systemctl --user stop "$(unit_name "$1")" 2>/dev/null || true ;;
    nohup)
      local p pid i; p=$(pid_file "$1")
      [ -f "$p" ] || return 0
      pid=$(cat "$p")
      if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
        for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
      fi
      rm -f "$p"
      ;;
    none) ;;
  esac
}

wait_healthy() { # name
  [ "$SUPERVISOR" = none ] && return 0
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if ( load_env; "$APP_ROOT/current/bin/$1" healthcheck >/dev/null 2>&1 ); then return 0; fi
    sleep 1
  done
  return 1
}

# Atomic switch where GNU mv -T is available (Linux); a plain ln -sfn elsewhere.
switch_to() { # release-dir-name
  if mv --help 2>&1 | grep -q -- '-T'; then
    ln -sfn "releases/$1" "$APP_ROOT/current.tmp"
    mv -fT "$APP_ROOT/current.tmp" "$APP_ROOT/current"
  else
    ln -sfn "releases/$1" "$APP_ROOT/current"
  fi
}

seed_config() { # release-dir
  mkdir -p "$APP_ROOT/conf" "$APP_ROOT/data" "$APP_ROOT/logs" "$APP_ROOT/run"
  [ -d "$1/conf.defaults" ] || return 0
  (cd "$1/conf.defaults" && find . -type f) | while read -r f; do
    if [ ! -e "$APP_ROOT/conf/$f" ]; then
      mkdir -p "$(dirname "$APP_ROOT/conf/$f")"
      cp "$1/conf.defaults/$f" "$APP_ROOT/conf/$f"
      log "seeded conf/${f#./}"
    fi
  done
}

prune() {
  local current previous keep=$KEEP
  current=$(current_release); previous=$(cat "$APP_ROOT/run/previous" 2>/dev/null || true)
  # Oldest first by modification time; never remove current or previous.
  ls -1tr "$APP_ROOT/releases" 2>/dev/null | grep -v '^\.' | head -n "-$keep" | while read -r r; do
    [ "$r" = "$current" ] || [ "$r" = "$previous" ] && continue
    rm -rf "${APP_ROOT:?}/releases/$r" && log "pruned release $r"
  done
}

# ---- commands -----------------------------------------------------------------------------------

cmd_install() {
  PLATFORM=${PLATFORM:-$(host_platform)}
  local file
  work=$(mktemp -d "$APP_ROOT/.install.XXXXXX") # global: the EXIT trap runs after this function returns
  trap 'rm -rf "${work:-}"' EXIT

  if [ -n "$COORDS" ]; then
    [ -n "$REPO_URL" ] || die "--repo-url (or INSTALL_REPO_URL) is required with --coordinates"
    IFS=: read -r group name version <<< "$COORDS"
    [ -n "${version:-}" ] || die "--coordinates must be GROUP:NAME:VERSION"
    file="$name-$version-$PLATFORM.tar.gz"
    local url="${REPO_URL%/}/${group//.//}/$name/$version/$file"
    log "downloading $url"
    curl_get "$url" "$work/$file"
    curl_get "$url.sha256" "$work/$file.sha256"
    SHA=$(tr -d ' \n\r' < "$work/$file.sha256" | cut -c1-64)
  elif [ -n "$ARCHIVE" ]; then
    file=$(basename "$ARCHIVE"); cp "$ARCHIVE" "$work/$file"
  else
    die "install needs --coordinates or --archive"
  fi

  if [ -n "$SHA" ]; then
    [ "$(sha256_of "$work/$file")" = "$SHA" ] || die "SHA-256 mismatch for $file — refusing to install"
    log "checksum verified"
  else
    echo "WARNING: no checksum supplied; installing unverified archive" >&2
  fi

  mkdir -p "$work/x"
  tar -xzf "$work/$file" -C "$work/x"
  local top; top=$(ls -1 "$work/x")
  [ "$(echo "$top" | wc -l)" -eq 1 ] && [ -d "$work/x/$top" ] || die "archive must contain exactly one top-level directory"
  local name version platform
  name=$(manifest_field "$work/x/$top" name); version=$(manifest_field "$work/x/$top" version)
  platform=$(manifest_field "$work/x/$top" platform)
  [ -n "$name" ] && [ -n "$version" ] || die "META-INF/bundle.json missing or unreadable"
  [ "$platform" = "$(host_platform)" ] || die "bundle is for $platform, this machine is $(host_platform)"

  mkdir -p "$APP_ROOT/releases"
  if [ -e "$APP_ROOT/releases/$version" ]; then
    $FORCE || die "release $version already installed (use --force to reinstall)"
    [ "$(current_release)" = "$version" ] && die "release $version is current; switch away before forcing a reinstall"
    rm -rf "${APP_ROOT:?}/releases/$version"
  fi
  mv "$work/x/$top" "$APP_ROOT/releases/$version"
  "$APP_ROOT/releases/$version/bin/$name" version >/dev/null || die "bin/$name version failed"
  seed_config "$APP_ROOT/releases/$version"

  resolve_supervisor
  local previous; previous=$(current_release)
  [ -n "$previous" ] && stop_app "$(manifest_field "$APP_ROOT/releases/$previous" name)"
  switch_to "$version"
  log "current -> releases/$version"

  start_app "$name"
  if wait_healthy "$name"; then
    log "$name $version is healthy"
    # Recorded only on success, so a failed install never becomes a rollback target.
    [ -n "$previous" ] && [ "$previous" != "$version" ] && echo "$previous" > "$APP_ROOT/run/previous"
    prune
  else
    echo "ERROR: $name $version failed its healthcheck within ${HEALTH_TIMEOUT}s" >&2
    stop_app "$name"
    if [ -n "$previous" ]; then
      switch_to "$previous"
      start_app "$name"
      echo "Rolled back to $previous" >&2
    fi
    tail -n 30 "$APP_ROOT/logs/console.log" 2>/dev/null >&2 || true
    exit 1
  fi
}

cmd_rollback() {
  local previous current name
  previous=$(cat "$APP_ROOT/run/previous" 2>/dev/null || true)
  current=$(current_release)
  [ -n "$previous" ] && [ -d "$APP_ROOT/releases/$previous" ] || die "no previous release recorded"
  resolve_supervisor
  name=$(app_name)
  stop_app "$name"
  switch_to "$previous"
  echo "$current" > "$APP_ROOT/run/previous"
  start_app "$name"
  wait_healthy "$name" || die "$previous failed its healthcheck after rollback"
  log "rolled back $current -> $previous"
}

cmd_status() {
  local name; name=$(app_name)
  [ -n "$name" ] || die "nothing installed in $APP_ROOT"
  resolve_supervisor
  echo "app:      $name"
  echo "current:  $(current_release)"
  echo "previous: $(cat "$APP_ROOT/run/previous" 2>/dev/null || echo -)"
  echo "releases: $(ls -1 "$APP_ROOT/releases" | tr '\n' ' ')"
  if is_running "$name"; then echo "process:  running ($SUPERVISOR)"; else echo "process:  stopped ($SUPERVISOR)"; fi
  if ( load_env; "$APP_ROOT/current/bin/$name" healthcheck >/dev/null 2>&1 ); then echo "health:   healthy"; else echo "health:   unhealthy"; fi
}

case "$COMMAND" in
  install) cmd_install ;;
  rollback) cmd_rollback ;;
  status) cmd_status ;;
  start) resolve_supervisor; start_app "$(app_name)"; wait_healthy "$(app_name)" ;;
  stop) resolve_supervisor; stop_app "$(app_name)" ;;
  restart) resolve_supervisor; n=$(app_name); stop_app "$n"; start_app "$n"; wait_healthy "$n" ;;
  *) sed -n '2,32p' "$0"; exit 64 ;;
esac
