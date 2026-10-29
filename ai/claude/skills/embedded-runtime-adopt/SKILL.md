---
name: embedded-runtime-adopt
description: Set up the java-app or node-app Gradle plugin in an existing project so it ships as one archive with its own JDK or Node.js runtime - settings plugin, build configuration, the run/healthcheck contract, configuration defaults, targets and the corporate binary repository. Use when asked to add, adopt, or migrate to the embedded runtime plugins, or to package an app with its own runtime.
---

# Adopt the embedded runtime plugins

## 1. Settings and build script

- Gradle 9.8 or newer (`./gradlew --version`; upgrade with `./gradlew wrapper --gradle-version 9.8.2`).
- In `settings.gradle.kts`, apply the settings plugin with a version. It declares every repository
  the build needs (preferring them over any in build scripts), so remove `repositories {}` blocks
  from the build scripts:
  ```kotlin
  plugins { id("io.github.fiftieshousewife.java-app.settings") version "<latest>" }
  ```
- In `build.gradle.kts`, apply `io.github.fiftieshousewife.java-app` with no version, keep the
  `application { mainClass = ... }` block, and add `javaApp { javaVersion = <the release it targets> }`.
  Remove other JDK toolchain or packaging plugins that now overlap (shadow/fat-jar distributions,
  jlink plugins, `foojay-resolver`).
- For Node.js: the `node-app.settings` and `node-app` plugins next to `package.json` and
  `package-lock.json` (required), with `nodeApp { entryPoint = "<main file under src/>" }` and
  `buildScript = "build"` if the app compiles TypeScript.

## 2. The app contract

The launcher `bin/<app>` runs `run`, `healthcheck` and `version`. Make the app:
- listen on `APP_PORT` when set;
- when started with `--healthcheck`, call the running instance (for example `GET /health` on
  `APP_PORT`) and exit 0 if healthy, non-zero otherwise, within a few seconds. An optional
  second argument, `live` or `ready`, is for Kubernetes probes (Spring Boot: the actuator's
  `liveness` and `readiness` groups); ignoring it is fine;
- Node.js only: handle SIGTERM (close the server, then exit), since in a container node runs as
  PID 1 and otherwise ignores it;
- read overrides from `APP_CONF_DIR`. Java: keep defaults in `src/main/resources` (Spring Boot:
  `SPRING_CONFIG_ADDITIONAL_LOCATION=$APP_CONF_DIR/`); Node.js: in `src/deploy/conf/`, which ships
  as `conf.defaults/`, the fallback when `APP_CONF_DIR` has no file.

## 3. Build and fix modules

Run `./gradlew build`. It packages the archive, starts it and waits for the health check. If the
smoke test fails on a missing JDK class, use the `embedded-runtime-jlink-modules` skill.

## 4. Targets, packaging and the corporate network

- Every OS/CPU deployed to: `targets("linux-x64", "linux-aarch64", "windows-x64")`.
- An RPM for Linux servers: `rpm { enabled = true }`. Archives are zip for Windows targets and
  tar.gz for Linux and macOS.
- Behind a corporate binary repository (Artifactory, Nexus): if `settings.gradle.kts` already
  declares the repository, set `binaryRepo.repository=<its name>` in the project's
  `gradle.properties`; its URL and credentials are reused, whatever the credential properties are
  called, so don't copy them anywhere. Otherwise set `binaryRepo.url` (and `binaryRepoUsername` /
  `binaryRepoPassword`) once per machine in `~/.gradle/gradle.properties`, never in the project.
  JDKs and Node.js then come from the binary repository's runtimes repository. If they live
  elsewhere, set `javaApp.jdk.source` / `nodeApp.node.source` to a mirror URL, an absolute path or
  a repository name beside the binary repository's, with `javaApp.jdk.pattern` /
  `nodeApp.node.pattern` when the file names differ from that layout.
- `appDockerfile` creates the project's root `Dockerfile` and keeps the block between
  `# >>> embedded-runtime` and `# <<< embedded-runtime` up to date; commit it, and add instructions
  after the block, never inside it. A Dockerfile without the block is the project's own; it copies
  the CPU being built in two layers, `COPY --chown=1001:0 layers/${TARGETARCH}/runtime/ /`, then
  `COPY --chown=1001:0 layers/${TARGETARCH}/app/ /`, whose trees give the same layers in every build. Other images of the app go in `Dockerfile.<name>` beside it;
  for a Java app on UBI micro, follow the `embedded-runtime-ubi-micro` skill (micro lacks zlib).

## 5. Done when

`./gradlew build` passes, `./gradlew runApp` serves the app, and the README of the project says how
to deploy the archive (see `docs/packaging.md` in the plugin repository).
