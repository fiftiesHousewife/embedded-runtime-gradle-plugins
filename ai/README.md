# AI assistant files

The assistant files the plugins ship to projects that use them, one source for every assistant.
`./gradlew aiSkills` writes them into a project where Copilot and Devin look; the Claude Code plugin
in `claude/` carries its own copies, which `./gradlew syncAiSkills` updates (`./gradlew check` fails
while they are out of date).

| Source | In a project | Read by |
|---|---|---|
| `skills/<name>/SKILL.md` | `.agents/skills/` | GitHub Copilot (agent mode in VS Code, Visual Studio and JetBrains IDEs, the Copilot app and CLI, the cloud agent, code review) and Devin, in the open Agent Skills format. Each skill is also a slash command (`/embedded-runtime-adopt`) in Copilot and `@skills:embedded-runtime-adopt` in Devin |
| `skills/<name>/SKILL.md` | the plugin's `claude/skills/` | Claude Code, through the `embedded-runtime` plugin in `claude/` (marketplace in `/.claude-plugin/`) |
| `hooks/embedded-runtime-check-gradle-build.sh` | `.github/hooks/`, and the plugin's `claude/hooks/scripts/` | The post-edit build check, shared by all three tools |
| `copilot/hooks/*.json` | `.github/hooks/` | Copilot's `postToolUse` hook (Copilot CLI and the cloud agent, which reads it from the default branch; GitHub documents no hooks in the IDEs) |
| `devin/hooks.v1.json` | `.devin/hooks.v1.json` | The Devin CLI's `PostToolUse` hook, on its `write`, `edit` and `apply_patch` tools |

This repository doesn't install them on itself; Copilot's instructions for working here are in
`/.github/copilot-instructions.md`.

## The post-edit hook

After the agent edits a Gradle build file (`*.gradle.kts`, `*.gradle`, `gradle.properties`,
`libs.versions.toml`), the hook runs `./gradlew -p <project> -q help` and, if the build no longer
configures, hands the error back to the agent in the form each one documents:

| Agent | How the error reaches the model | Set by |
|---|---|---|
| Claude Code | stderr, exit code 2 | the default |
| Copilot | `{"additionalContext": …}` on stdout | `EMBEDDED_RUNTIME_HOOK_FORMAT=copilot` in its hook config |
| Devin CLI | `{"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": …}}` on stdout | `EMBEDDED_RUNTIME_HOOK_FORMAT=devin` in its hook command |

The script finds the edited file in `file_path` or `path` (also inside Copilot's escaped `toolArgs`
string), or in a patch's `*** Update File:` lines. `.github/scripts/test-agent-hooks.sh` calls it
the way each agent does, in CI.

## In projects that use the plugins

- Copilot and Devin: `./gradlew aiSkills` writes the skills to `.agents/skills/`, the hook to
  `.github/hooks/`, and `.devin/hooks.v1.json` if the project has none. Every file is named
  `embedded-runtime-*`, so a project's own skills and hooks are left alone.
- Claude Code: install the plugin, which brings the skills and the hook:
  ```
  /plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
  ```
