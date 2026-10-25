# Embedded runtime Gradle plugins

[![CI](https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins/actions/workflows/ci.yml/badge.svg)](https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%E2%80%9327-orange.svg)](docs/java.md)
[![Gradle](https://img.shields.io/badge/Gradle-9.8%2B-02303A.svg)](https://gradle.org)

Package a Java or Node.js application **with its own runtime**: your code, its libraries, and a
pinned JDK or Node.js, in one archive per target or as an RPM. Servers and containers need nothing
installed, and the archive that passes CI is the archive that runs in production.

| Plugin | For | Embeds |
|---|---|---|
| `io.github.fiftieshousewife.java-app` | Java and Kotlin/JVM apps | A jlink-trimmed JDK from Temurin, Oracle, Corretto, Zulu, Liberica, Microsoft or SapMachine |
| `io.github.fiftieshousewife.node-app` | Node.js apps | The official Node.js runtime |
| `io.github.fiftieshousewife.app-smoke-test` | Any build checking a published app | |

The first two each come with a settings plugin (`….java-app.settings`, `….node-app.settings`) that
sets up every repository the build needs, so projects declare none of their own.

**Requirements:** Gradle 9.8 or newer. Java apps can target Java 11 to 27, whatever JDK Gradle
itself runs on.

## Quick start

```kotlin
// settings.gradle.kts
plugins {
    id("io.github.fiftieshousewife.java-app.settings") version "1.0.0"
}
rootProject.name = "orders-service"
```

```kotlin
// build.gradle.kts
plugins {
    id("io.github.fiftieshousewife.java-app")   // no version: the settings plugin supplies it
}

group = "com.example"
version = "1.4.2"

application { mainClass = "com.example.orders.Main" }

javaApp {
    javaVersion = 21
    jvmArgs = listOf("-XX:MaxRAMPercentage=75")
}
```

For Kotlin, also apply `kotlin("jvm")`. For Node.js, use the `node-app` plugins next to
`package.json` and `package-lock.json`, with `nodeApp { entryPoint = "server.mjs" }`.

```bash
./gradlew build      # compile, test, package, then start the archive and check its health
./gradlew runApp     # run the packaged app in the foreground
```

The result is `build/distributions/orders-service-1.4.2-linux-x64.tar.gz`, named for the machine you
built on. `build` only passes if the packaged app started and reported healthy. Your app needs to
read `APP_PORT` and answer `--healthcheck`; see [What your app needs to do](docs/your-app.md).

The [samples](samples) are a plain Java HTTP service, a Spring Boot service, a Node.js service, and a
service built and run as a container image when Docker is available.

## Tasks

`./gradlew build` runs everything a release needs: `assemble` builds the archives (and RPMs), and
`check` runs your tests, checks every archive against the runtime policy, and starts this machine's
archive. `<Target>` stands for a target in task names: `LinuxX64`, `LinuxAarch64`, `MacAarch64`,
`MacX64`, `WindowsX64`. Settings are in `javaApp {}` or `nodeApp {}` unless they start with `-P`,
which marks a Gradle property (`gradle.properties`, `~/.gradle/gradle.properties` or `-P`). Every
setting and its default is in the [reference](docs/reference.md).

### Building the archive

| Task | What it does | Settings |
|---|---|---|
| `appArchive` | Builds every target's archive into `build/distributions/`; part of `assemble` | `targets(…)`, `archiveFormat(…)`, `appName`; `-PjavaApp.targets`, `-PjavaApp.archiveFormat` |
| `<target>DistTar`, `<target>DistZip` | One target's archive, `<app>-<version>-<target>.tar.gz` (or `.tar`, `.zip`) | as above; `configDefaults` (shipped as `conf.defaults/`) |
| `installDist` | Lays out this machine's app with its runtime in `build/install/<app>` | as above |
| `install<Target>Dist` | The same for one target, e.g. `installLinuxX64Dist` | as above |
| `startScripts` | Java: writes `bin/<app>` and `bin/<app>.bat`, which start the embedded runtime | `jvmArgs`, `application { applicationDefaultJvmArgs }`, `runtimeOptions("locked")`; `-PjavaApp.runtimeOptions` |
| `appLaunchers<Target>` | Node: writes the target's launcher | `entryPoint`, `nodeArgs`, `security { tlsMinVersion, tlsCipherList }`, `runtimeOptions("locked")`; `-PnodeApp.runtimeOptions` |
| `appManifest<Target>` | Writes `META-INF/app.json`: name, version, target, runtime, git commit | `appName` |
| `appSbom<Target>` | Writes `META-INF/sbom.cdx.json`, a CycloneDX SBOM including the runtime; skipped when you supply your own | `sbom = …` (your own SBOM instead) |
| `appVerifyPolicy<Target>` | Checks the assembled app against the organisation's runtime policy; every archive depends on it, and it is skipped when no policy is set | `-PjavaApp.securityPolicy` or `-PjavaApp.securityPolicyArtifact` (`nodeApp.…` for Node) |

### Running and checking it

| Task | What it does | Settings |
|---|---|---|
| `appSmokeTest` | Unpacks this machine's archive, checks `version`, starts the app on a free port and waits for its health check; part of `check` | `smokeTestEnabled`, `smokeTestTimeout` |
| `runApp` | Runs this machine's packaged app in the foreground | `-Pargs="…"` (arguments for the app) |
| `appVerifyPublishable` | Stops `publish` for archives that were never started on this machine | `allowUntestedPublish`; `-PjavaApp.allowUntestedPublish` |

### Java runtime

| Task | What it does | Settings |
|---|---|---|
| `extractJdk<Target>`, `extractJmods<Target>` | Downloads and unpacks the target's pinned JDK (and, from Java 24, its separate jmods) | `javaVersion`, `jdkVersion`; `-PjavaApp.jdk.distribution`, `-PjavaApp.jdk.<feature>`, `-PjavaApp.jdk.url`, `-PjavaApp.jdk.localPath`, `-PjavaApp.jdk.<feature>.<target>.path`, `-PbinaryRepo.url` |
| `jdeps` | Lists the JDK modules the code refers to | |
| `jlinkFindModules` | Runs the app, adding each JDK module it turns out to need, until it is healthy | `smokeTestTimeout` |
| `jlink<Target>` | Links the target's trimmed runtime | `additionalModules`, `jlinkModules`, `jlinkOptions` |
| `customiseRuntime<Target>` | Applies trusted certificates, security properties, the overlay and the runtime policy to it | `security { … }`, `runtimeOverlay`; `-PjavaApp.securityPolicy[Artifact]` |

### Node.js

| Task | What it does | Settings |
|---|---|---|
| `extractNode<Target>`, `nodeRuntime<Target>` | Downloads the target's pinned Node.js and selects its `node` binary and licence | `nodeVersion`; `-PnodeApp.node.version`, `-PnodeApp.node.url`, `-PbinaryRepo.url` |
| `npmrc` | Writes the npm configuration for the registry | `-PnodeApp.npm.registry`, `-PnodeApp.npm.caFile`, `-PbinaryRepo.repo.npm` |
| `npmInstall`, `npmBuild`, `npmTest` | `npm ci`, your build script, your test script (`npmTest` is part of `check`) | `buildScript`, `testScript` |
| `npmInstallProduction<Target>` | `npm ci --omit=dev` for one target, away from the working tree | `ignoreInstallScripts`, `portableInstallScripts` |
| `verifyNodeModules<Target>` | Fails if a dependency's install script left output for the build machine in another target's `node_modules` | `ignoreInstallScripts`, `portableInstallScripts` |
| `nodeTrustedCertificates` | Bundles trusted CAs and the policy's into `security/trusted-cas.pem` | `security { trustedCertificates }` |

### RPMs, containers and publishing

| Task | What it does | Settings |
|---|---|---|
| `appRpm`, `appRpm<Target>` | Builds an RPM for each Linux target; part of `assemble` when enabled | `rpm { enabled, packageName, release, summary, license, vendor, url, user, systemd }`; `-PjavaApp.rpm` |
| `appPublishRpm` | Uploads the RPMs to a YUM repository; part of `publish` when one is set | `rpm { repositoryUrl }`; `-PbinaryRepo.repo.rpm`, `-PbinaryRepoUsername`, `-PbinaryRepoPassword` |
| `appDockerfile` | Writes the Dockerfile: your own if there is one, else a generated one | `dockerfile` |
| `appDockerContext` | Writes a container build context: the Dockerfile plus a Linux archive | `dockerfile`, `targets(…)` |
| `publish` | Gradle's own: publishes every target's archive under one version, with the target as the classifier | `-PbinaryRepo.url`, `-PbinaryRepo.repo.releases`, `-PbinaryRepo.repo.snapshots` |
| `aiSkills` | Writes the AI assistant skills and the build-check hook into the project | |

In a deployment pipeline, the `app-smoke-test` plugin adds its own `appSmokeTest`, which smoke-tests
a published release declared as `smokeTest("com.example:orders-service:1.4.2")`, picking the archive
built for the machine it runs on. It takes `-PappSmokeTest.securityPolicy` or
`-PappSmokeTest.securityPolicyArtifact` to refuse an archive that breaks the runtime policy.

## Documentation

| | |
|---|---|
| [What your app needs to do](docs/your-app.md) | The launcher, environment, configuration, what's in the archive, the SBOM |
| [Building for Linux, Mac and Windows](docs/targets.md) | Several targets from one build, and which ones are tested |
| [Java: modules, hardening and JDKs](docs/java.md) | jdeps and jlink, certificates and security policy, choosing a JDK distribution |
| [Packaging and deploying](docs/packaging.md) | tar.gz, tar, zip, RPM and container images; Gradle distributions and launchers |
| [Performance tuning](docs/performance.md) | Java Flight Recorder on the embedded runtime, heap, GC and startup |
| [Publishing](docs/publishing.md) | Maven repositories, signing, smoke-testing a published app |
| [Behind a corporate binary repository](docs/binary-repository.md) | One setting per machine; the repositories to create |
| [Reference](docs/reference.md) | Every setting and its default, every Gradle property |
| [Packaging standards](docs/packaging-standards.md) | The conventions each format follows, and the formats not built |
| [Ship the runtime with the app](docs/article.md) | The article: why runtimes belong in the release, and how these plugins do it |

### AI assistant skills

Skills that adopt the plugins in a project, find the JDK modules for jlink, upgrade dependencies and
runtimes, and tune performance with Java Flight Recorder, plus a hook that checks the build still configures after the agent edits a build file:

- **GitHub Copilot** (agent mode in VS Code, Visual Studio and JetBrains IDEs, the Copilot app and
  CLI, the cloud agent) **and Devin**: `./gradlew aiSkills` writes them into your project.
- **Claude Code**: install the plugin:
  ```
  /plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
  ```

API documentation: https://fiftieshousewife.github.io/embedded-runtime-gradle-plugins/ (from each
plugin's KDoc; also in the javadoc jar of every publication).

Working on the plugins themselves: [AGENTS.md](AGENTS.md).

## Licence

[Apache License 2.0](LICENSE).
