#!/usr/bin/env bash
# Tests the post-edit hook shared by Claude Code, Copilot and Devin, called the way each calls it.
#  - Claude Code / Devin: tool_input.file_path; a broken build exits 2 with the error on stderr.
#  - Copilot: toolArgs as an escaped JSON string with a relative path and cwd; a broken build exits 0
#    with {"additionalContext": ...} on stdout, which is how a postToolUse hook reaches the model.
set -euo pipefail
hook=ai/hooks/embedded-runtime-check-gradle-build.sh
build=samples/java-service/build.gradle.kts
claude() { printf '{"tool_name":"Edit","tool_input":{"file_path":"%s"}}' "$PWD/$1"; }
copilot() { printf '{"cwd":"%s","toolName":"edit","toolArgs":"{\\"path\\":\\"%s\\",\\"old_str\\":\\"a\\"}"}' "$PWD" "$1"; }

claude README.md | "$hook"
claude "$build" | "$hook"
copilot "$build" | EMBEDDED_RUNTIME_HOOK_FORMAT=copilot "$hook" > copilot.out
[ ! -s copilot.out ] || { echo "Copilot mode printed output for a good build:" >&2; cat copilot.out >&2; exit 1; }

cp "$build" "$build.bak"
trap 'mv "$build.bak" "$build"; rm -f hook.err copilot.out' EXIT
echo 'javaApp { thisDoesNotExist = 1 }' >> "$build"

if claude "$build" | "$hook" 2> hook.err; then echo "missed a broken build" >&2; exit 1; else status=$?; fi
[ "$status" -eq 2 ] || { echo "exited $status, expected 2" >&2; exit 1; }
grep -q thisDoesNotExist hook.err || { echo "error not passed on:" >&2; cat hook.err >&2; exit 1; }

copilot "$build" | EMBEDDED_RUNTIME_HOOK_FORMAT=copilot "$hook" > copilot.out
python3 -c 'import json,sys; m=json.load(open("copilot.out"))["additionalContext"]; assert "thisDoesNotExist" in m, m' \
  || { echo "Copilot mode did not return the error as additionalContext:" >&2; cat copilot.out >&2; exit 1; }
echo "hook: ignores other files, passes a good build, reports a broken one to Claude/Devin (exit 2) and Copilot (additionalContext)"
