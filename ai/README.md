# AI assistant files

The assistant files the plugins ship to projects that use them, one source for every assistant.
Every build of such a project writes them where Copilot and Devin look (`aiSkills`, part of
`assemble`) and points Claude Code at the plugin in `claude/`, which carries its own copies;
`./gradlew syncAiSkills` updates those (`./gradlew check` fails while they are out of date).

| Source | In a project | Read by |
|---|---|---|
| `skills/<name>/SKILL.md` | `.agents/skills/` | GitHub Copilot (agent mode in VS Code, Visual Studio and JetBrains IDEs, the Copilot app and CLI, the cloud agent, code review) and Devin, in the open Agent Skills format. Each skill is also a slash command (`/embedded-runtime-adopt`) in Copilot and `@skills:embedded-runtime-adopt` in Devin |
| `skills/<name>/SKILL.md` | the plugin's `claude/skills/` | Claude Code, through the `embedded-runtime` plugin in `claude/` (marketplace in `/.claude-plugin/`) |
| `hooks/embedded-runtime-check-gradle-build.sh` | `.github/hooks/`, and the plugin's `claude/hooks/scripts/` | The post-edit build check, shared by all three tools |
| `copilot/hooks/*.json` | `.github/hooks/` | Copilot's `postToolUse` hook (Copilot CLI and the cloud agent, which reads it from the default branch; GitHub documents no hooks in the IDEs) |
| `devin/hooks.v1.json` | `.devin/hooks.v1.json` | The Devin CLI's `PostToolUse` hook, on its `write`, `edit` and `apply_patch` tools |
| `/.claude-plugin/marketplace.json` | `.claude/settings.json` (`extraKnownMarketplaces`, `enabledPlugins`) | Claude Code, which offers the `embedded-runtime` plugin to everyone who opens the project |

This repository doesn't install them on itself: the samples set `javaApp.aiSkills=false` /
`nodeApp.aiSkills=false` in their `gradle.properties`, since the sources are here. Copilot's
instructions for working here are in `/.github/copilot-instructions.md`.

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

`aiSkills` runs with `assemble` (and so `build`), in the root project's directory, and writes a
file only when it differs; commit them:

- Copilot and Devin: the skills go to `.agents/skills/`, the hook to `.github/hooks/`, and
  `.devin/hooks.v1.json` if the project has none. Every file is named `embedded-runtime-*`, so a
  project's own skills and hooks are left alone.
- Claude Code: the marketplace and `"embedded-runtime@embedded-runtime-gradle-plugins": true` are
  added to `.claude/settings.json`, keeping everything else in it (a plugin the project
  disabled stays disabled); the plugin brings the skills and the hook.
- `-PjavaApp.aiSkills=false` (or `nodeApp.aiSkills=false`, for example in `gradle.properties`)
  turns it off.

Claude Code users in a project that doesn't use the plugins can install it themselves:

```
/plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
```
