#!/bin/sh
# PostToolUse hook: after Claude edits a Gradle build file, check that the build still configures.
# On failure the error is handed back to Claude (exit 2) so it can fix what it just broke.
# Plain POSIX sh with no jq or Python, so it runs wherever Claude Code runs hooks.
# EMBEDDED_RUNTIME_HOOK_GRADLE_ARGS adds Gradle arguments, e.g. --offline.
payload=$(cat)
file=$(printf '%s' "$payload" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
case "$file" in
  *.gradle.kts|*.gradle|*/gradle.properties|*/libs.versions.toml) ;;
  *) exit 0 ;;
esac

# The edited file's build: the nearest directory with a settings file. Then the nearest wrapper.
up_to() { # marker... ; prints the nearest directory at or above $start holding any marker
  d=$start
  while :; do
    for m in "$@"; do [ -f "$d/$m" ] && { echo "$d"; return 0; }; done
    [ "$d" = "/" ] || [ "$d" = "." ] && return 1
    d=$(dirname "$d")
  done
}
start=$(dirname "$file")
project=$(up_to settings.gradle.kts settings.gradle) || exit 0
start=$project
wrapper=$(up_to gradlew) || exit 0

# shellcheck disable=SC2086 # the extra arguments are meant to be split
if ! output=$(cd "$wrapper" && sh ./gradlew -p "$project" -q help ${EMBEDDED_RUNTIME_HOOK_GRADLE_ARGS:-} 2>&1); then
  echo "The Gradle build in $project no longer configures after editing $file:" >&2
  printf '%s\n' "$output" | grep -v -e '^Picked up' -e 'FSEvents' | tail -n 30 >&2
  exit 2
fi
exit 0
