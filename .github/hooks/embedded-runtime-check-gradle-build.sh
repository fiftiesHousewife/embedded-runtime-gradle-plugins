#!/bin/sh
# Post-edit hook shared by Claude Code, GitHub Copilot and the Devin CLI: after the agent edits a
# Gradle build file, check that the build still configures, and hand any error back to the agent.
#
# Input (JSON on stdin): Claude Code and Devin send tool_input.file_path; Copilot sends toolArgs
# (sometimes as an escaped JSON string) with path or file_path, and cwd for relative paths.
# Output: by default exit 2 with the error on stderr (Claude Code, Devin); with
# EMBEDDED_RUNTIME_HOOK_FORMAT=copilot, exit 0 with {"additionalContext": "..."} on stdout,
# the only way a Copilot postToolUse hook reaches the model.
# EMBEDDED_RUNTIME_HOOK_GRADLE_ARGS adds Gradle arguments, e.g. --offline.
# Plain POSIX sh with no jq or Python, so it runs wherever these agents run hooks.
payload=$(cat)
field() { # first value of the named JSON string field, quotes plain or backslash-escaped
  printf '%s' "$payload" | sed -n "s/.*\\\\\\{0,1\\}\"$1\\\\\\{0,1\\}\"[[:space:]]*:[[:space:]]*\\\\\\{0,1\\}\"\\([^\"\\\\]*\\)\\\\\\{0,1\\}\".*/\\1/p" | head -n 1
}
file=$(field file_path)
[ -n "$file" ] || file=$(field path)
case "$file" in
  /*|?:*) ;;
  "") exit 0 ;;
  *) cwd=$(field cwd); file="${cwd:-$PWD}/$file" ;;
esac
case "$file" in
  *.gradle.kts|*.gradle|*/gradle.properties|*/libs.versions.toml) ;;
  *) exit 0 ;;
esac

# The edited file's build: the nearest directory with a settings file. Then the nearest wrapper.
up_to() { # marker...: the nearest directory at or above $start holding any of them
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
output=$(cd "$wrapper" && sh ./gradlew -p "$project" -q help ${EMBEDDED_RUNTIME_HOOK_GRADLE_ARGS:-} 2>&1) && exit 0
message="The Gradle build in $project no longer configures after editing $file:
$(printf '%s\n' "$output" | grep -v -e '^Picked up' -e 'FSEvents' | tail -n 30)"
if [ "${EMBEDDED_RUNTIME_HOOK_FORMAT:-}" = copilot ]; then
  escaped=$(printf '%s' "$message" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/	/\\t/g' | awk 'BEGIN { ORS = "\\n" } { print }')
  printf '{"additionalContext":"%s"}\n' "$escaped"
  exit 0
fi
printf '%s\n' "$message" >&2
exit 2
