#!/usr/bin/env bash
# Mirrors approved runtime archives into the curated Artifactory layout read by
# the io.github.fiftieshousewife java-app and node-app plugins (artifactory.repo.runtimes, default
# "runtimes"):
#
#   runtimes/temurin/jdk/<version_>/jdk-<version_>-<target>.<ext>
#   runtimes/temurin/jmods/<version_>/jmods-<version_>-<target>.<ext>   Java 24+, whose JDKs lack jmods/
#   runtimes/nodejs/node/<version>/node-<version>-<target>.<ext>
#
# Every archive is checked against the upstream published SHA-256 before upload, and uploaded with
# that checksum so Artifactory verifies it too. Run it from a machine that can reach both the
# internet and Artifactory, when a new runtime is approved (e.g. a quarterly Java CPU).
#
# Usage:
#   curate-runtimes.sh jdk  21.0.12.1+1 [target ...]
#   curate-runtimes.sh node 24.18.1     [target ...]
#   targets default to: linux-x64 linux-aarch64 windows-x64 mac-aarch64
#
# Environment:
#   ARTIFACTORY_URL      e.g. https://artifactory.example.com/artifactory   (required)
#   ARTIFACTORY_REPO     default runtimes
#   ARTIFACTORY_USER / ARTIFACTORY_TOKEN   deploy credentials (passed to curl on stdin)
#   TEMURIN_BASE_URL     default https://github.com/adoptium
#   NODE_BASE_URL        default https://nodejs.org/dist
#   CURL_CA_BUNDLE       optional corporate CA bundle
set -euo pipefail

KIND=${1:?usage: curate-runtimes.sh jdk|node VERSION [target ...]}
VERSION=${2:?version required}
shift 2
TARGETS=("$@"); [ ${#TARGETS[@]} -gt 0 ] || TARGETS=(linux-x64 linux-aarch64 windows-x64 mac-aarch64)
ART=${ARTIFACTORY_URL:?ARTIFACTORY_URL is required}
REPO=${ARTIFACTORY_REPO:-runtimes}
TEMURIN=${TEMURIN_BASE_URL:-https://github.com/adoptium}
NODE=${NODE_BASE_URL:-https://nodejs.org/dist}

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { curl --fail --silent --show-error --location --retry 3 --output "$2" "$1"; }
upload() { # file path checksum
  local args=(--fail --silent --show-error -T "$1" -H "X-Checksum-Sha256: $3")
  if [ -n "${ARTIFACTORY_USER:-}" ]; then
    printf 'user = "%s:%s"\n' "$ARTIFACTORY_USER" "$ARTIFACTORY_TOKEN" | curl "${args[@]}" --config - "${ART%/}/$REPO/$2" >/dev/null
  else
    curl "${args[@]}" --netrc-optional "${ART%/}/$REPO/$2" >/dev/null
  fi
  echo "uploaded $REPO/$2"
}

# Fetches $2 (named $3) to the work directory, checks it against SHA-256 $4 (or the .sha256.txt
# beside it when $4 is empty) and uploads it as $1.
mirror() {
  local target=$1 url=$2 name=$3 expected=$4 actual
  fetch "$url" "$work/$name"
  if [ -z "$expected" ]; then fetch "$url.sha256.txt" "$work/$name.sha256.txt"; expected=$(cut -d' ' -f1 "$work/$name.sha256.txt"); fi
  actual=$(sha "$work/$name")
  if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then
    echo "checksum mismatch for $name: expected '${expected}', got $actual" >&2
    exit 1
  fi
  upload "$work/$name" "$target" "$actual"
}

# One Temurin asset ($1: jdk or jmods) for target $2 ($3 os, $4 arch, $5 extension).
temurin() {
  local image=$1 p=$2 os=$3 arch=$4 ext=$5 feature under name
  feature=${VERSION%%.*}; feature=${feature%%+*}
  under=${VERSION//+/_}
  name="OpenJDK${feature}U-${image}_${arch}_${os}_hotspot_${under}.${ext}"
  mirror "temurin/$image/$under/$image-$under-$p.$ext" \
    "${TEMURIN%/}/temurin${feature}-binaries/releases/download/jdk-${VERSION//+/%2B}/$name" "$name" ""
}

for p in "${TARGETS[@]}"; do
  os=${p%-*}; arch=${p#*-}
  ext=tar.gz; [ "$os" = windows ] && ext=zip
  case "$KIND" in
    jdk)
      temurin jdk "$p" "$os" "$arch" "$ext"
      feature=${VERSION%%.*}
      [ "$feature" -lt 24 ] || temurin jmods "$p" "$os" "$arch" "$ext"
      ;;
    node)
      nos=$os; [ "$os" = windows ] && nos=win; [ "$os" = mac ] && nos=darwin
      narch=$arch; [ "$arch" = aarch64 ] && narch=arm64
      name="node-v$VERSION-$nos-$narch.$ext"
      [ -f "$work/SHASUMS256.txt" ] || fetch "${NODE%/}/v$VERSION/SHASUMS256.txt" "$work/SHASUMS256.txt"
      mirror "nodejs/node/$VERSION/node-$VERSION-$p.$ext" "${NODE%/}/v$VERSION/$name" "$name" \
        "$(awk -v f="$name" '$2 == f { print $1 }' "$work/SHASUMS256.txt")"
      ;;
    *) echo "unknown kind $KIND (jdk|node)" >&2; exit 64 ;;
  esac
done
