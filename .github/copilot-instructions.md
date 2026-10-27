# Working in this repository

Gradle plugins that package a Java or Node.js app with its own runtime. Read [AGENTS.md](../AGENTS.md)
before changing code: it explains the layout, the build commands, where things are tested and the
conventions (ktlint and detekt are blocking; every version lives in `gradle/libs.versions.toml`).

- Build and test with `./gradlew check`; one module's functional tests with
  `./gradlew -p plugins :java-app:functionalTest`.
- User documentation is in `docs/`; keep it, `AGENTS.md` and `BACKLOG.md` current with every change.
- The plugins' AI assistant skills and post-edit hook live in `ai/` (see `ai/README.md`); after
  editing them, run `./gradlew syncAiSkills` to update the Claude Code plugin's copies.
