# AI assistant files

One source for every assistant, copied to where each tool looks. Edit only the files here, then run
`./gradlew syncAiSkills`; `./gradlew check` fails while a copy is out of date.

| Source | Copied to | Read by |
|---|---|---|
| `skills/<name>/SKILL.md` | `.agents/skills/` | GitHub Copilot (agent mode in VS Code, Visual Studio and JetBrains IDEs, the Copilot app and CLI, the cloud agent, code review) and Devin, in the open Agent Skills format. Each skill is also a slash command (`/embedded-runtime-adopt`) in Copilot and `@skills:embedded-runtime-adopt` in Devin |
| `skills/<name>/SKILL.md` | `claude/skills/` | Claude Code, through the `embedded-runtime` plugin in `claude/` (marketplace in `/.claude-plugin/`) |
| `hooks/embedded-runtime-check-gradle-build.sh` | `claude/hooks/scripts/`, `.github/hooks/` | The post-edit build check, shared by all three tools |
| `copilot/hooks/*.json` | `.github/hooks/` | Copilot's `postToolUse` hook (CLI, cloud agent, VS Code) |
| `devin/hooks.v1.json` | `.devin/hooks.v1.json` | The Devin CLI's `PostToolUse` hook |
| `copilot/copilot-instructions.md` | `.github/copilot-instructions.md` | Copilot Chat, code review and the cloud agent, for work on this repository |

The tools fix those destination paths, which is why the copies exist.

## The post-edit hook

After the agent edits a Gradle build file (`*.gradle.kts`, `*.gradle`, `gradle.properties`,
`libs.versions.toml`), the hook runs `./gradlew -p <project> -q help` and, if the build no longer
configures, hands the error back to the agent: Claude Code and Devin read it from stderr with exit
code 2; Copilot only passes a `postToolUse` hook's output to the model as JSON `additionalContext`,
which the script writes when `EMBEDDED_RUNTIME_HOOK_FORMAT=copilot` (set in the Copilot hook config).
`.github/scripts/test-agent-hooks.sh` tests it both ways in CI.

## In projects that use the plugins

- Copilot and Devin: `./gradlew aiSkills` writes the skills to `.agents/skills/`, the hook to
  `.github/hooks/`, and `.devin/hooks.v1.json` if the project has none. Every file is named
  `embedded-runtime-*`, so a project's own skills and hooks are left alone.
- Claude Code: install the plugin, which brings the skills and the hook:
  ```
  /plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
  ```
