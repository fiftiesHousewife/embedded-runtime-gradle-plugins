# Backlog

Work that has been asked for and not yet done, newest decisions first. [ROADMAP.md](ROADMAP.md)
holds the researched gaps; this file holds the queue. Move an item to the commit log, not to a
"done" list, when it lands.

Last updated 2026-10-25.

## Next: Gradle-native launchers and distributions

Done on 2026-10-25 ([plans/gradle-distributions.md](plans/gradle-distributions.md)): Java launchers
come from Gradle's own `startScripts`, every target is a Gradle distribution, plain `installDist`
installs this machine's app with its runtime, and the custom installer is gone.

## AI tooling

Done 2026-10-25: everything in `ai/` (see `ai/README.md`): skills in the open Agent Skills format
shared by Copilot and Devin (and slash commands in both), one post-edit build-check hook wired for
Claude Code, Copilot and the Devin CLI and tested in CI both ways, Copilot repository instructions,
and `./gradlew aiSkills` to install them in a project. Prompt files were dropped: skills replace them.

| Item | Notes |
|---|---|
| **`.claude/settings.json`** | Sandbox blocks writing it; the snippet that auto-suggests the marketplace to collaborators is in the conversation of 2026-10-25. Owner to add |
| **Unverified hook details** | Researched 2026-10-25 from GitHub's and Devin's hook docs: Copilot runs hooks in its CLI and cloud agent only (none documented for VS Code, Visual Studio or JetBrains); Devin's edit tools are `write`, `edit`, `apply_patch` and its PostToolUse feedback is `hookSpecificOutput.additionalContext`, both now used. Still undocumented anywhere: the file-path argument names of Copilot's `edit`/`create` and Devin's `write`/`edit`, and the shape of Devin's `apply_patch` input (the script tries `file_path`, `path` and `*** Update File:` lines), and whether Devin's cloud sessions run hooks. Settle by logging one real payload from each agent |


## Code quality

| Item | Notes |
|---|---|
| **GitHub Pages for the API docs** | `.github/workflows/api-docs.yml` builds them and deploys them once the owner sets Pages to "GitHub Actions" in the repository settings; until then the deploy job is skipped with a notice, not failed |
| **Build speed** | Measured 2026-10-25: Java's functional tests were 95% of test time (830 s summed, against 45 s for everything else). Half of it was the fixture gzipping a copy of the test JDK for every target of every test; it now builds one archive per test JVM and hard-links it (830 s to 400 s; the full `check` takes about 2 minutes here). `--profile` on the fixture builds puts what is left in `jlink` (about 5 s a build), JDK extraction (about 3 s) and the smoke test. Next, if needed: a shared build cache for the fixture builds, at the cost of tests that assert tasks really ran |

## Verification and CI

| Item | Notes |
|---|---|
| Windows functional tests | One functional test now runs on Windows: an installed JDK given by `javaApp.jdk.<feature>.windows-x64.path`, built, zipped and smoke-tested through the `.bat` launcher. The rest still skip there, because the fixtures build fake download sites with the system `tar` and `sh`; porting them means Windows-layout JDK and Node zips (commons-compress is already a dependency) |
| GitHub API access for the agent | Unauthenticated API calls hit the rate limit; `gh` config is unreadable in the sandbox |


## Older open items

- The company's RPM-packaged JDK: real file name, install path, and whether it ships `jmods/`
  (naming described as `jdk<major>_<patch>-64_linux.x86_64.rpm`).
- Publishing to Maven Central and the Gradle Plugin Portal (needs the owner's credentials).
- Proposals awaiting a decision: Jib, SLSA provenance, CDS/AOT cache opt-in.
- Article: the "HUFs" anecdote is unsourced; "Try it" uses version 1.0.0, which is not published.
- Gradle deprecation warnings in this repository's own build: detekt 2.0.0-alpha.6 removed the
  only one due in Gradle 10 (`ReportingExtension.file`). `Configuration.setVisible`, due in Gradle 11,
  is still called by Dokka (2.2.0 and 2.3.0-Beta) and detekt 2's alpha; bump them when fixed
  releases ship, and move detekt to 2.0.0 final when it is out.
- Stale `~/.m2/repository/io/github/fiftieshousewife/platform*` folders on the owner's machine.
