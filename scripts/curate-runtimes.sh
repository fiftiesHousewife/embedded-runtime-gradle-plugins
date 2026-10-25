#!/usr/bin/env bash
# Mirrors approved runtime archives into the curated binary repository layout read by
# the io.github.fiftieshousewife java-app and node-app plugins (binaryRepo.repo.runtimes, default
# "runtimes"):
#
#   runtimes/temurin/jdk/<version_>/jdk-<version_>-<target>.<ext>
#   runtimes/temurin/jmods/<version_>/jmods-<version_>-<target>.<ext>   Java 24+, whose JDKs lack jmods/
#   runtimes/nodejs/node/<version>/node-<version>-<target>.<ext>
#
# Every archive is checked against the upstream published SHA-256 before upload, and uploaded with
# that checksum so the repository verifies it too (Artifactory and Nexus both do). Run it from a machine that can reach both the
# internet and the binary repository, when a new runtime is approved (e.g. a quarterly Java CPU).
#
# Usage:
#   curate-runtimes.sh jdk  21.0.12.1+1 [target ...]                   (Temurin, the default)
#   DISTRIBUTION=oracle curate-runtimes.sh jdk 25.0.3 [target ...]
#   DISTRIBUTION=zulu SHA256_linux_x64=<hex> curate-runtimes.sh jdk 21.54.107-ca-jdk21.0.13 linux-x64
#   curate-runtimes.sh node 24.18.1     [target ...]
#   targets default to: linux-x64 linux-aarch64 windows-x64 mac-aarch64
#
# Versions are in the distribution's own form, as in javaApp.jdk.<feature>. Temurin, Oracle,
# Microsoft and SapMachine publish a checksum beside every archive, which is checked. Corretto, Zulu
# and Liberica don't: pass each target's SHA-256 from the vendor's release page as
# SHA256_<target with _ for ->; an archive without one is never uploaded.
#
# Environment:
#   BINARY_REPO_URL      e.g. https://repo.example.com   (required)
#   BINARY_REPO_RUNTIMES     default runtimes
#   BINARY_REPO_USER / BINARY_REPO_TOKEN   deploy credentials (passed to curl on stdin)
#   DISTRIBUTION         temurin (default), oracle, corretto, zulu, liberica, microsoft, sapmachine
#   TEMURIN_BASE_URL     default https://github.com/adoptium
#   NODE_BASE_URL        default https://nodejs.org/dist
#   CURL_CA_BUNDLE       optional corporate CA bundle
#   DRY_RUN=1            print what would be mirrored, with each URL and checksum; download nothing
set -euo pipefail

KIND=${1:?usage: curate-runtimes.sh jdk|node VERSION [target ...]}
VERSION=${2:?version required}
shift 2
TARGETS=("$@"); [ ${#TARGETS[@]} -gt 0 ] || TARGETS=(linux-x64 linux-aarch64 windows-x64 mac-aarch64)
ART=${BINARY_REPO_URL:-}
[ -n "$ART" ] || [ -n "${DRY_RUN:-}" ] || { echo "BINARY_REPO_URL is required" >&2; exit 64; }
REPO=${BINARY_REPO_RUNTIMES:-runtimes}
TEMURIN=${TEMURIN_BASE_URL:-https://github.com/adoptium}
NODE=${NODE_BASE_URL:-https://nodejs.org/dist}
DIST=${DISTRIBUTION:-temurin}

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { curl --fail --silent --show-error --location --retry 3 --output "$2" "$1"; }
upload() { # file path checksum
  local args=(--fail --silent --show-error -T "$1" -H "X-Checksum-Sha256: $3")
  if [ -n "${BINARY_REPO_USER:-}" ]; then
    printf 'user = "%s:%s"\n' "$BINARY_REPO_USER" "$BINARY_REPO_TOKEN" | curl "${args[@]}" --config - "${ART%/}/$REPO/$2" >/dev/null
  else
    curl "${args[@]}" --netrc-optional "${ART%/}/$REPO/$2" >/dev/null
  fi
  echo "uploaded $REPO/$2"
}

# Fetches $2 (named $3) to the work directory, checks it against SHA-256 $4 (or the .sha256.txt
# beside it when $4 is empty) and uploads it as $1.
mirror() {
  local target=$1 url=$2 name=$3 expected=$4 actual
  if [ -n "${DRY_RUN:-}" ]; then echo "would mirror $url -> $REPO/$target (sha256 ${expected:-from $url.sha256.txt})"; return; fi
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

# The vendor's download URL for target $1 ($2 os, $3 arch, $4 extension), as the plugin builds it.
vendor_url() {
  local os=$2 arch=$3 ext=$4 feature=${VERSION%%.*} v=${VERSION//+/%2B}
  case "$DIST" in
    oracle) echo "https://download.oracle.com/java/$feature/archive/jdk-${VERSION}_${os/mac/macos}-${arch}_bin.$ext" ;;
    corretto)
      local suffix=""; [ "$os" = windows ] && suffix=-jdk
      echo "https://corretto.aws/downloads/resources/$VERSION/amazon-corretto-$VERSION-${os/mac/macosx}-$arch$suffix.$ext" ;;
    zulu)
      local zos=$os; [ "$os" = mac ] && zos=macosx; [ "$os" = windows ] && zos=win
      echo "https://cdn.azul.com/zulu/bin/zulu$VERSION-${zos}_$arch.$ext" ;;
    liberica)
      local larch=$arch; [ "$arch" = x64 ] && larch=amd64
      echo "https://github.com/bell-sw/Liberica/releases/download/$v/bellsoft-jdk$v-${os/mac/macos}-$larch.$ext" ;;
    microsoft) echo "https://aka.ms/download-jdk/microsoft-jdk-$VERSION-${os/mac/macos}-$arch.$ext" ;;
    sapmachine) echo "https://github.com/SAP/SapMachine/releases/download/sapmachine-$VERSION/sapmachine-jdk-${VERSION}_${os/mac/macos}-${arch}_bin.$ext" ;;
    *) echo "unknown DISTRIBUTION $DIST" >&2; exit 64 ;;
  esac
}

# The vendor's published SHA-256 for $1 (the archive URL), or SHA256_<target> for vendors without one.
vendor_sha() {
  local url=$1 p=$2 var
  case "$DIST" in
    oracle) curl --fail --silent --show-error --location "$url.sha256" | cut -c1-64 ;;
    microsoft) curl --fail --silent --show-error --location "$url.sha256sum.txt" | cut -d' ' -f1 ;;
    sapmachine) local base=${url%.tar.gz}; base=${base%.zip}
      curl --fail --silent --show-error --location "$base.sha256.txt" | cut -d' ' -f1 ;;
    *) var="SHA256_${p//-/_}"; printf '%s' "${!var:-}" ;;
  esac
}

# Any distribution but Temurin: one JDK archive for target $1.
vendor_jdk() {
  local p=$1 os=${1%-*} arch=${1#*-} ext=tar.gz url sha name under=${VERSION//+/_}
  [ "$os" = windows ] && ext=zip
  url=$(vendor_url "$p" "$os" "$arch" "$ext")
  name=${url##*/}
  sha=$(vendor_sha "$url" "$p")
  [ -n "$sha" ] || { echo "no SHA-256 for $DIST $VERSION $p: set SHA256_${p//-/_} from the vendor's release page" >&2; exit 1; }
  mirror "$DIST/jdk/$under/jdk-$under-$p.$ext" "$url" "$name" "$sha"
}

for p in "${TARGETS[@]}"; do
  os=${p%-*}; arch=${p#*-}
  ext=tar.gz; [ "$os" = windows ] && ext=zip
  case "$KIND" in
    jdk)
      if [ "$DIST" != temurin ]; then vendor_jdk "$p"; continue; fi
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
