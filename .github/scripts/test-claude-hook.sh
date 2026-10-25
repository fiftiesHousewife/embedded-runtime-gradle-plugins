#!/usr/bin/env bash
# Tests the Claude Code plugin's PostToolUse hook the way Claude Code calls it: JSON on stdin.
# Exit 0 for a file that is not a build file and for a build that configures; exit 2, with the
# Gradle error on stderr, for a build file that no longer compiles.
set -euo pipefail
hook=ai/claude/hooks/check-gradle-build.sh
build=samples/java-service/build.gradle.kts
payload() { printf '{"tool_name":"Edit","tool_input":{"file_path":"%s"}}' "$PWD/$1"; }

payload README.md | "$hook"
payload "$build" | "$hook"

cp "$build" "$build.bak"
trap 'mv "$build.bak" "$build"' EXIT
echo 'javaApp { thisDoesNotExist = 1 }' >> "$build"
if payload "$build" | "$hook" 2> hook.err; then echo "hook missed a broken build" >&2; exit 1; else status=$?; fi
[ "$status" -eq 2 ] || { echo "hook exited $status, expected 2" >&2; exit 1; }
grep -q "thisDoesNotExist" hook.err || { echo "hook did not pass the error on:" >&2; cat hook.err >&2; exit 1; }
rm hook.err
echo "hook: ignores other files, passes a good build, reports a broken one"
