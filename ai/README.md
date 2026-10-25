# AI assistant files

One source for every assistant, copied to where each tool looks. Edit only the files here, then run
`./gradlew syncAiSkills`; `./gradlew check` fails while a copy is out of date.

| Source | Copied to | Read by |
|---|---|---|
| `skills/<name>/SKILL.md` | `.agents/skills/` | GitHub Copilot (agent mode in VS Code, Visual Studio and JetBrains IDEs, the Copilot app and CLI, the cloud agent and code review) and Devin, which follow the open Agent Skills format |
| `skills/<name>/SKILL.md` | `claude/skills/` | Claude Code, through the `embedded-runtime` plugin in `claude/` (installed from the marketplace in `/.claude-plugin/`) |
| `copilot/prompts/*.prompt.md` | `.github/prompts/` | Copilot Chat prompt files, for IDE users without agent mode |
| `copilot/copilot-instructions.md` | `.github/copilot-instructions.md` | Copilot Chat, code review and the cloud agent, for work on this repository |

The tools fix those destination paths, which is why the copies exist.

Projects that use the plugins get the skills and prompt files with `./gradlew aiSkills`, which writes
them into the project's `.agents/skills/` and `.github/prompts/`, for Copilot and Devin. Claude Code
users install the plugin instead:

```
/plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
```

`claude/hooks/` holds the plugin's hook: after Claude edits a Gradle build file, it checks that the
build still configures, and hands any error back to Claude.
