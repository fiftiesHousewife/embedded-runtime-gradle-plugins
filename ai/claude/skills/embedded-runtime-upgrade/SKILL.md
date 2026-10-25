---
name: embedded-runtime-upgrade
description: Upgrade a project built with the embedded runtime Gradle plugins to the latest versions - its libraries, Gradle plugins, the embedded JDK or Node.js, and the plugins themselves - and prove the packaged app still starts. Use when asked to upgrade, update dependencies, take a Java/Node security patch, bump to the latest, or review Renovate/Dependabot pull requests in such a project.
---

# Upgrade dependencies and the embedded runtime

The embedded JDK or Node.js is pinned by the plugin release, so most runtime upgrades are a plugin
upgrade. Every step ends with `./gradlew build`, which packages the archive, starts it and waits for
its health check: an upgrade is only done when that passes.

## 1. See what is out of date

- Libraries and plugins: if the build applies `com.github.ben-manes.versions`, run
  `./gradlew dependencyUpdates -Drevision=release` and read `build/dependencyUpdates/report.txt`.
  Otherwise list the versions in `gradle/libs.versions.toml` (or the build scripts) and check each
  against its repository (Maven Central, the Gradle Plugin Portal, or the organisation's
  binary repository when `binaryRepo.url` is set).
- The plugins: find `io.github.fiftieshousewife.java-app.settings` / `.node-app.settings` in
  `settings.gradle.kts` and compare with the latest release.
- The runtime the current release embeds: run `./gradlew appArchive` and read
  `build/app/<target>/<app>-<version>/META-INF/app.json` (laid out by `install<Target>Dist`) (`runtime.name` and `runtime.version`).

## 2. Upgrade in this order, building after each

1. **The plugins** (one version for both settings plugins). This moves the JDK pins for every
   vendor and Java release, the Node.js pin and the container base image together.
2. **Gradle itself** if the plugins need a newer one: `./gradlew wrapper --gradle-version <v>`, then
   run it again so the wrapper scripts update.
3. **Libraries, grouped**: a framework with its plugins and BOM in one step (for example every
   `org.springframework.boot` artifact), test libraries in another. Upgrade a group, build, commit.
4. **Major versions** that need code changes: prefer an OpenRewrite recipe
   (`org.openrewrite.rewrite` Gradle plugin, e.g. `org.openrewrite.java.migrate.UpgradeToJava21`)
   over hand edits, then build.

## 3. A runtime patch before the next plugin release

Only when a security fix can't wait. In `gradle.properties`:

```properties
javaApp.jdk.21=21.0.13+9          # in the configured distribution's own version form
nodeApp.node.version=24.18.2
```

Version forms differ by distribution (`javaApp.jdk.distribution`): temurin `21.0.13+9`, corretto
`21.0.13.0.10`, zulu `21.54.107-ca-jdk21.0.13`, liberica `21.0.13+11`, oracle, microsoft and
sapmachine `21.0.13`. A wrong form is rejected before download. Vendors publish a release one
platform at a time over several days: confirm the build exists for every target in
`javaApp.targets` before pinning it. Remove the override once a plugin release includes the patch.

## 4. Raising the Java release

Changing `javaApp { javaVersion = N }` changes the compile toolchain and the embedded runtime
together. Gradle then refuses any library published for a newer JVM than N (the
`org.gradle.jvm.version` attribute), and some libraries need a minimum version for N. After
raising it, run `./gradlew build`; if the smoke test reports a missing JDK class, use the
`jlink-modules` skill.

## 5. Finish

- `./gradlew build` passes for this machine's target; for cross-target builds, CI on each target
  (or `app-smoke-test` on each target) has passed.
- Summarise each change: old -> new version, why (security fix, feature, required by another upgrade),
  and anything held back with the reason (for example "library X stays on 4.x: 5.x needs Java 21").
