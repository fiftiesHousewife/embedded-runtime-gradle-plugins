#!/usr/bin/env bash
# Mirrors approved runtime archives into the curated Artifactory layout read by
# io.github.fiftieshousewife.platform (platform.artifactory.repo.runtimes, default "runtimes"):
#
#   runtimes/temurin/jdk/<version_>/jdk-<version_>-<platform>.<ext>
#   runtimes/nodejs/node/<version>/node-<version>-<platform>.<ext>
#
# Every archive is checked against the upstream published SHA-256 before upload, and uploaded with
# that checksum so Artifactory verifies it too. Run it from a machine that can reach both the
# internet and Artifactory, when a new runtime is approved (e.g. a quarterly Java CPU).
#
# Usage:
#   curate-runtimes.sh jdk  21.0.12.1+1 [platform ...]
#   curate-runtimes.sh node 24.18.1     [platform ...]
#   platforms default to: linux-x64 linux-aarch64 windows-x64 mac-aarch64
#
# Environment:
#   ARTIFACTORY_URL      e.g. https://artifactory.example.com/artifactory   (required)
#   ARTIFACTORY_REPO     default runtimes
#   ARTIFACTORY_USER / ARTIFACTORY_TOKEN   deploy credentials (passed to curl on stdin)
#   TEMURIN_BASE_URL     default https://github.com/adoptium
#   NODE_BASE_URL        default https://nodejs.org/dist
#   CURL_CA_BUNDLE       optional corporate CA bundle
set -euo pipefail

KIND=${1:?usage: curate-runtimes.sh jdk|node VERSION [platform ...]}
VERSION=${2:?version required}
shift 2
PLATFORMS=("$@"); [ ${#PLATFORMS[@]} -gt 0 ] || PLATFORMS=(linux-x64 linux-aarch64 windows-x64 mac-aarch64)
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

for p in "${PLATFORMS[@]}"; do
  os=${p%-*}; arch=${p#*-}
  ext=tar.gz; [ "$os" = windows ] && ext=zip
  case "$KIND" in
    jdk)
      feature=${VERSION%%.*}; feature=${feature%%+*}
      under=${VERSION//+/_}
      name="OpenJDK${feature}U-jdk_${arch}_${os}_hotspot_${under}.${ext}"
      url="${TEMURIN%/}/temurin${feature}-binaries/releases/download/jdk-${VERSION//+/%2B}/$name"
      fetch "$url" "$work/$name"
      fetch "$url.sha256.txt" "$work/$name.sha256.txt"
      expected=$(cut -d' ' -f1 "$work/$name.sha256.txt")
      target="temurin/jdk/$under/jdk-$under-$p.$ext"
      ;;
    node)
      nos=$os; [ "$os" = windows ] && nos=win; [ "$os" = mac ] && nos=darwin
      narch=$arch; [ "$arch" = aarch64 ] && narch=arm64
      name="node-v$VERSION-$nos-$narch.$ext"
      [ -f "$work/SHASUMS256.txt" ] || fetch "${NODE%/}/v$VERSION/SHASUMS256.txt" "$work/SHASUMS256.txt"
      fetch "${NODE%/}/v$VERSION/$name" "$work/$name"
      expected=$(awk -v f="$name" '$2 == f { print $1 }' "$work/SHASUMS256.txt")
      target="nodejs/node/$VERSION/node-$VERSION-$p.$ext"
      ;;
    *) echo "unknown kind $KIND (jdk|node)" >&2; exit 64 ;;
  esac
  actual=$(sha "$work/$name")
  if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then
    echo "checksum mismatch for $name: expected '${expected}', got $actual" >&2
    exit 1
  fi
  upload "$work/$name" "$target" "$actual"
done
