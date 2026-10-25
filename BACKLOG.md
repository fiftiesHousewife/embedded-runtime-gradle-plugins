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
| **Spring Boot sample** | A real Spring Boot service packaged by java-app. Measure and document the effect on package size: fat jar + full JDK vs jlinked runtime + libraries, compressed and unpacked, per target. Feed the numbers into the article |
| **Java with Docker sample** | Builds the image from `appDockerContext` and runs it. Runs only when Docker is reachable (skipped, not failed, otherwise); locally colima can be started for it |

## Article

| Item | Notes |
|---|---|
| Package-size effect | From the Spring Boot sample; measured numbers only |
| Revisit after the Gradle rework | The launcher and archive sections should describe `startScripts` templates and per-target distributions |

## AI tooling

| Item | Notes |
|---|---|
| **One `ai/` folder** | Move `claude-plugin/` to `ai/claude/`; the marketplace manifest stays at `.claude-plugin/marketplace.json` (it must be at the repo root) and points into `ai/claude` |
| **GitHub Copilot** | Must not assume the Copilot CLI: cover the IDE (VS Code, JetBrains, Visual Studio) via repository custom instructions (`.github/copilot-instructions.md`, path-scoped `.github/instructions/*.instructions.md`) and prompt files (`.github/prompts/*.prompt.md`), plus the coding agent. Mirror the three skills (adopt, jlink-modules, upgrade). Research current file formats before writing |
| **Devin** | Equivalent knowledge/playbooks for the same three tasks. Research Devin's current repository conventions before writing |
| **Hooks** | Enable and test hooks where they help (e.g. format with spotless after edits to Kotlin; run unit tests of the touched module). Prove each fires |
| **`.claude/settings.json`** | Sandbox blocks writing it; the snippet that auto-suggests the marketplace to collaborators is in the conversation of 2026-10-25 and in the README section on skills. Owner to add |
| **Performance tuning skill** | Skill plus a docs section on tuning an embedded-runtime app, built around Java Flight Recorder (`-XX:StartFlightRecording`, `jfr print`, JDK Mission Control), GC choice and heap sizing in containers; needs `jdk.jfr` in the runtime |

## Code quality

| Item | Notes |
|---|---|
| **Comment review** | Delete trivial comments; prefer meaningful function and class names to comments; keep KDoc on public API |
| **Publish API docs** | KDoc as javadoc jars (Dokka) with the publications, and browsable HTML (e.g. GitHub Pages) |
| **Build speed** | Functional tests now run in parallel forks; measure, and consider a Gradle build scan or `--profile` to find the rest |

## Verification and CI

| Item | Notes |
|---|---|
| Windows CI after the LF fix | Confirm the `ci` run on `c03d1f0` or later is green on Windows |
| systemd step | `.github/scripts/verify-rpm.sh` now runs the unit under a real systemd (UBI init image); has not run in CI yet |
| Windows pre-installed JDKs | `javaApp.jdk.<feature>.windows-x64.path` has unit tests only; functional tests skip on Windows (fixtures use system `tar`). Add a Windows functional path |
| GitHub API access for the agent | Unauthenticated API calls hit the rate limit; `gh` config is unreadable in the sandbox |

## Older open items

- The company's RPM-packaged JDK: real file name, install path, and whether it ships `jmods/`
  (naming described as `jdk<major>_<patch>-64_linux.x86_64.rpm`).
- `deploy/curate-runtimes.sh` mirrors only Temurin and Node.js.
- Node.js has no security customisation (`NODE_EXTRA_CA_CERTS`, TLS options) and no runtime policy.
- Publishing to Maven Central and the Gradle Plugin Portal (needs the owner's credentials).
- Proposals awaiting a decision: Jib, SLSA provenance, CDS/AOT cache opt-in.
- Article: the "HUFs" anecdote is unsourced; "Try it" uses version 1.0.0, which is not published.
- Gradle deprecation warnings from third-party build plugins (`ReportingExtension.file`,
  `Configuration.setVisible`), affecting only this repository's own build.
- Stale `~/.m2/repository/io/github/fiftieshousewife/platform*` folders on the owner's machine.
