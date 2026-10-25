# Backlog

Work that has been asked for and not yet done, newest decisions first. [ROADMAP.md](ROADMAP.md)
holds the researched gaps; this file holds the queue. Move an item to the commit log, not to a
"done" list, when it lands.

Last updated 2026-10-25.

## Next: Gradle-native launchers and distributions

Done on 2026-10-25 ([plans/gradle-distributions.md](plans/gradle-distributions.md)): Java launchers
come from Gradle's own `startScripts`, every target is a Gradle distribution, plain `installDist`
installs this machine's app with its runtime, and the custom installer is gone.

## Samples

| Item | Notes |
|---|---|
| **Docker sample: a real run** | `samples/docker-service` builds the image and smoke-tests the container, skipping cleanly without a Linux Docker daemon (verified). Its container run is unverified until CI's Ubuntu job runs it, or colima is started locally |


## AI tooling

Done 2026-10-25: everything in `ai/` (see `ai/README.md`): skills in the open Agent Skills format
shared by Copilot and Devin (and slash commands in both), one post-edit build-check hook wired for
Claude Code, Copilot and the Devin CLI and tested in CI both ways, Copilot repository instructions,
and `./gradlew aiSkills` to install them in a project. Prompt files were dropped: skills replace them.

| Item | Notes |
|---|---|
| **`.claude/settings.json`** | Sandbox blocks writing it; the snippet that auto-suggests the marketplace to collaborators is in the conversation of 2026-10-25. Owner to add |
| **Unverified hook details** | The exact argument names Copilot's `edit`/`create` and Devin's edit tools use for the file path (the script accepts `file_path` and `path`); whether Copilot's cloud agent and Devin's cloud sessions run these hooks; hooks in Visual Studio and JetBrains |


## Code quality

| Item | Notes |
|---|---|
| **GitHub Pages for the API docs** | `.github/workflows/api-docs.yml` builds and deploys them; the owner must set Pages to "GitHub Actions" in the repository settings |
| **Build speed** | Functional tests now run in parallel forks; measure, and consider a Gradle build scan or `--profile` to find the rest |

## Verification and CI

| Item | Notes |
|---|---|
| Windows pre-installed JDKs | `javaApp.jdk.<feature>.windows-x64.path` has unit tests only; functional tests skip on Windows (fixtures use system `tar`). Add a Windows functional path |
| GitHub API access for the agent | Unauthenticated API calls hit the rate limit; `gh` config is unreadable in the sandbox |


## Older open items

- The company's RPM-packaged JDK: real file name, install path, and whether it ships `jmods/`
  (naming described as `jdk<major>_<patch>-64_linux.x86_64.rpm`).
- Node.js has no security customisation (`NODE_EXTRA_CA_CERTS`, TLS options) and no runtime policy.
- Publishing to Maven Central and the Gradle Plugin Portal (needs the owner's credentials).
- Proposals awaiting a decision: Jib, SLSA provenance, CDS/AOT cache opt-in.
- Article: the "HUFs" anecdote is unsourced; "Try it" uses version 1.0.0, which is not published.
- Gradle deprecation warnings from third-party build plugins (`ReportingExtension.file`,
  `Configuration.setVisible`), affecting only this repository's own build.
- Stale `~/.m2/repository/io/github/fiftieshousewife/platform*` folders on the owner's machine.
