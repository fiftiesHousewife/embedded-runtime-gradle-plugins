---
name: adopt
description: Set up the java-app or node-app Gradle plugin in an existing project so it ships as one archive with its own JDK or Node.js runtime - settings plugin, build configuration, the run/healthcheck contract, configuration defaults, targets and Artifactory. Use when asked to add, adopt, or migrate to the embedded runtime plugins, or to package an app with its own runtime.
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
  `APP_PORT`) and exit 0 if healthy, non-zero otherwise, within a few seconds;
- read configuration from `APP_CONF_DIR` first and fall back to the shipped `conf.defaults/`.
Move default config files to `src/deploy/conf/`.

## 3. Build and fix modules

Run `./gradlew build`. It packages the archive, starts it and waits for the health check. If the
smoke test fails on a missing JDK class, use the `jlink-modules` skill.

## 4. Targets, packaging and the corporate network

- Every OS/CPU deployed to: `targets("linux-x64", "linux-aarch64", "windows-x64")`.
- An RPM for Linux servers: `rpm { enabled = true }`; a zip instead of tar.gz: `archiveFormat("zip")`.
- Behind a corporate binary repository (Artifactory, Nexus): set `binaryRepo.url` (and credentials) once per machine in
  `~/.gradle/gradle.properties`, never in the project.
- An existing Dockerfile at the project root is used as-is by `appDockerContext`; otherwise one is
  generated. Its `COPY` should take the extracted archive from `app/`.

## 5. Done when

`./gradlew build` passes, `./gradlew runApp` serves the app, and the README of the project says how
to deploy the archive (see `docs/packaging.md` in the plugin repository).
