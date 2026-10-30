# Embedded runtime Gradle plugins

[![CI](https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins/actions/workflows/ci.yml/badge.svg)](https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%E2%80%9327-orange.svg)](docs/java.md)
[![Gradle](https://img.shields.io/badge/Gradle-9.8%2B-02303A.svg)](https://gradle.org)

Package a Java or Node.js application **with its own runtime**: your code, its libraries, and a
pinned JDK or Node.js, as an app archive and a runtime archive per target, or as an RPM. Servers and
containers need nothing installed, and what passes CI is what runs in production. The runtime archive
is the same bytes in every release that doesn't change it, so a repository that stores by checksum keeps it once.

| Plugin | For | Embeds |
|---|---|---|
| `io.github.fiftieshousewife.java-app` | Java and Kotlin/JVM apps | A jlink-trimmed JDK from Oracle, Temurin, Corretto, Zulu, Liberica, Microsoft or SapMachine |
| `io.github.fiftieshousewife.node-app` | Node.js apps | The official Node.js runtime |
| `io.github.fiftieshousewife.app-smoke-test` | Any build checking a published app | |

The first two each come with a settings plugin (`….java-app.settings`, `….node-app.settings`) that
sets up every repository the build needs, so projects declare none of their own.

**Requirements:** Gradle 9.8 or newer. Java apps can target Java 11 to 27, whatever JDK Gradle
itself runs on.

## Quick start

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()                         // the plugins are published to Maven Central
        gradlePluginPortal()
    }
}
plugins {
    id("io.github.fiftieshousewife.java-app.settings") version "0.0.5"
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
    jvmArgs = listOf("-Xss512k")               // after the production defaults below
}
```

For Kotlin, also apply `kotlin("jvm")`.

```bash
./gradlew build      # compile, test, package, then start the archive and check its health
./gradlew runApp     # run the packaged app in the foreground
```

The result is `build/distributions/orders-service-1.4.2-linux-x64.tar.gz`, named for the machine you
built on. `build` only passes if the packaged app started and reported healthy. Your app needs to
read `APP_PORT` and answer `--healthcheck`; see [What your app needs to do](docs/your-app.md).

### A complete Java service

Everything below is optional; each block says what it changes. Linux on x64 and ARM plus Windows,
a JDK trimmed to what the app uses, the organisation's CAs and TLS rules, an RPM, and publication:

```kotlin
// build.gradle.kts
plugins {
    id("io.github.fiftieshousewife.java-app")
}

group = "com.example"
version = "1.4.2"
description = "Order service"                  // the RPM's summary

application {
    mainClass = "com.example.orders.Main"
}

javaApp {
    appName = "orders-service"                 // launcher, archive and RPM name; default: the project name

    // Target platforms: an app and a runtime archive each, zip for Windows and tar.gz for Linux. A Mac or Windows
    // machine builds the Linux ones directly, with no containers. Only this machine's archive can be
    // started, so only it is smoke-tested.
    targets("linux-x64", "linux-aarch64", "windows-x64")

    // The runtime: compiled with and embedded. The vendor and exact version are Gradle properties,
    // below; the plugin pins a version only for Temurin's LTS releases.
    javaVersion = 25                           // 11 to 27

    // jlink links only the modules jdeps finds, plus these, which code loads by name at runtime
    // (`./gradlew jlinkFindModules` finds them by running the app).
    additionalModules = listOf("jdk.crypto.ec", "java.naming", "jdk.localedata")

    // How the JVM starts. By default it gets 75% of the machine's or container's memory, exits on
    // OutOfMemoryError and leaves a heap dump and crash logs in $APP_LOG_DIR; jvmArgs come after
    // these, so they win.
    jvmArgs = listOf("-Xss512k", "-XX:+UseG1GC", "-XX:+DisableAttachMechanism")

    // CAs and encryption, applied to every target's runtime after jlink.
    security {
        trustedCertificates.from("security/certs")                    // PEM or DER, added to cacerts
        trustedCertificate(uri("https://pki.example.com/root-ca.pem"), sha256 = "9f86d081884c7d65…")
        disableAlgorithms(                                            // added to the JDK's own list
            "tls",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        )
        properties.put("networkaddress.cache.ttl", "30")              // any java.security property
        lockProperties = true                                         // no -Djava.security.properties
    }

    // Packaging beyond the archive.
    license = "LicenseRef-Proprietary"         // SPDX: the image label and the RPM's licence
    configDefaults = file("src/deploy/conf")   // shipped as conf.defaults/ and an RPM's /etc/<name>/
    rpm {
        enabled = true                         // an RPM per Linux target, installed under /opt
        systemd = true                         // a hardened systemd unit, run as its own user
    }
}
```

Put the machine's settings in `gradle.properties` or `~/.gradle/gradle.properties`, or pass them as
`ORG_GRADLE_PROJECT_…` variables in CI:

```properties
# the JDK vendor, and its exact version
javaApp.jdk.distribution=oracle
javaApp.jdk.25=25.0.3
# the repository settings.gradle.kts already declares: its server and credentials are used for
# runtimes, npm and publishing (see "Behind a corporate binary repository")
binaryRepo.repository=artifactory
# the organisation's runtime policy
javaApp.securityPolicy=com.example.platform:java-runtime-policy:7
# the launcher refuses to start while JAVA_TOOL_OPTIONS, APP_JAVA_OPTS or a jvm.options file
# would add options of their own
javaApp.runtimeOptions=locked
# publish the targets this machine couldn't start
javaApp.allowUntestedPublish=true
# how long, in seconds, the started archive may take to get healthy
javaApp.smokeTestTimeout=90
```

`./gradlew build` then gives an app archive and a runtime archive for each of the three targets in
`build/distributions/`, and two RPMs. `publish` uploads the archives as two modules under one version,
`<app>` and `<app>-runtime`, with the target as the classifier; the RPMs are not published. The
runtime policy is checked before any archive is built. See [Building for Linux, Mac and
Windows](docs/targets.md), [Java: modules, hardening and JDKs](docs/java.md) and [Packaging and
deploying](docs/packaging.md).

### A Node.js service

Next to `package.json` and `package-lock.json`, with the `node-app` settings plugin in
`settings.gradle.kts`:

```kotlin
// build.gradle.kts
plugins {
    id("io.github.fiftieshousewife.node-app")
}

group = "com.example"
version = "3.1.0"

nodeApp {
    entryPoint = "server.mjs"                  // under app/; started by `bin/<app> run`
    buildScript = "build"                      // npm script run before packaging (then dist/ is packaged)
    testScript = "test"                        // npm script run by `check`

    targets("linux-x64", "linux-aarch64")
    installScripts = listOf("esbuild")         // vetted packages whose install scripts run; default: none

    nodeArgs = listOf("--enable-source-maps", "--max-old-space-size=512")

    // CAs and encryption: the CAs go into security/trusted-cas.pem, which the launcher passes to
    // Node as NODE_EXTRA_CA_CERTS; the TLS settings become node options.
    security {
        trustedCertificates.from("security/certs")
        tlsMinVersion = "1.3"
        tlsCipherList = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"
    }
}
```

The Node.js version is `nodeApp.node.version` (default: the plugin's pin), and
`nodeApp.runtimeOptions=locked` also refuses `NODE_OPTIONS` and `NODE_TLS_REJECT_UNAUTHORIZED`. See
[What your app needs to do](docs/your-app.md#nodejs-trusted-cas-and-tls) and
[Behind a corporate binary repository](docs/binary-repository.md) for npm registries and CAs.

### How big the archives are

Measured for linux-x64 against Temurin 21.0.12.1+1, on 25 October 2026, in megabytes of a million
bytes:

| | Compressed | Unpacked |
|---|---|---|
| Full Temurin 21 JDK, for comparison | 207.5 MB | 353 MB |
| Temurin 21 JRE, for comparison | 52.1 MB | |
| **Spring Boot 4.1 web service with Actuator: both archives** | **58.9 MB** | **85.7 MB** |
| of which the runtime archive (13 modules found by jdeps, 18 with what they need) | 39.3 MB | 63.9 MB |
| of which the app archive: Spring Boot, Tomcat, Jackson and the service (50 jars) | 19.7 MB | 21.8 MB |
| **Plain HTTP service on the JDK's own server: both archives** | **21.0 MB** | **40.9 MB** |
| of which the runtime archive (`java.base`, `jdk.httpserver`; 26 MB of it is the JVM itself) | | 40.9 MB |

A whole Spring Boot service, runtime included, is about the size of the vendor's JRE download
alone. The runtime grows with the modules the app needs: Spring's `java.desktop`, pulled in by
`java.beans`, is its largest. Details in the article's [Spring Boot
measurements](docs/article.md#what-it-does-to-a-spring-boot-service).

The [samples](samples) are a plain Java HTTP service, a Spring Boot service, a Node.js service, a
service built and run as a container image when Docker is available, and a deployment check that
verifies a release's Sigstore signature before starting it.

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
| `appArchive` | Builds every target's archive into `build/distributions/`; part of `assemble` | `targets(…)`, `appName`; `-PjavaApp.targets` |
| `<target>DistTar`, `<target>DistZip` | One target's archive, `<app>-<version>-<target>.tar.gz` (`.zip` for Windows) | as above; `configDefaults` (shipped as `conf.defaults/`) |
| `installDist` | Lays out this machine's app with its runtime in `build/install/<app>` | as above |
| `install<Target>Dist` | The same for one target, e.g. `installLinuxX64Dist` | as above |
| `startScripts` | Java: writes `bin/<app>` and `bin/<app>.bat`, which start the embedded runtime | `runtimeDefaults`, `jvmArgs`, `application { applicationDefaultJvmArgs }`; `-PjavaApp.runtimeOptions` |
| `appLaunchers<Target>` | Node: writes the target's launcher | `entryPoint`, `nodeArgs`, `security { tlsMinVersion, tlsCipherList }`; `-PnodeApp.runtimeOptions` |
| `appManifest<Target>` | Writes `META-INF/app.json`: name, version, target, runtime, git commit | `appName` |
| `appSbom<Target>` | Writes `META-INF/sbom.cdx.json`, a CycloneDX SBOM including the runtime; skipped when you supply your own | `sbom = …` (your own SBOM instead) |
| `appVerifyPolicy<Target>` | Checks the assembled app against the organisation's runtime policy; every archive depends on it, and it is skipped when no policy is set | `securityPolicy`; `-PjavaApp.securityPolicy` (`nodeApp.…` for Node) |

### Running and checking it

| Task | What it does | Settings |
|---|---|---|
| `appSmokeTest` | Unpacks this machine's archive, checks `version`, starts the app on a free port and waits for its health check; part of `check` | `smokeTestEnabled`; `-PjavaApp.smokeTestTimeout` |
| `runApp` | Runs this machine's packaged app in the foreground | `-Pargs="…"` (arguments for the app) |
| `checkRuntimeSupport` | Fails if the embedded runtime's release line has reached end of life; warns if it soon will or a newer patch is out. Run it on a schedule; not part of `check` | `-PjavaApp.runtimeSupportUrl`, `-PjavaApp.endOfLifeWarningDays`, `-PjavaApp.allowEndOfLife` (`nodeApp.…` for Node) |

### Java runtime

| Task | What it does | Settings |
|---|---|---|
| `downloadJdk<Target>` | With `javaApp.jdk.source=yum:…`: finds the JDK package for the pin and the target's CPU in the YUM repository's metadata, downloads it and checks its SHA-256 | `-PjavaApp.jdk.source`, `-PjavaApp.jdk.pattern` |
| `extractJdk<Target>`, `extractJmods<Target>` | Downloads and unpacks the target's pinned JDK (and, from Java 24, its separate jmods) | `javaVersion`; `-PjavaApp.jdk.distribution`, `-PjavaApp.jdk.<feature>`, `-PjavaApp.jdk.source`, `-PjavaApp.jdk.pattern`, `-PjavaApp.jdk.<feature>.<target>.path`, `-PbinaryRepo.repository` or `-PbinaryRepo.url` |
| `jlinkFindModules` | Runs the app, adding each JDK module it turns out to need, until it is healthy, and reports what to add to `additionalModules` | `-PjavaApp.smokeTestTimeout` |
| `jlink<Target>` | Links the target's trimmed runtime | `additionalModules`, `moduleSets` |
| `customiseRuntime<Target>` | Applies trusted certificates, security properties, the overlay and the runtime policy to it | `security { … }`, `runtimeOverlay`, `securityPolicy`; `-PjavaApp.securityPolicy` |

### Node.js

| Task | What it does | Settings |
|---|---|---|
| `extractNode<Target>`, `nodeRuntime<Target>` | Downloads the target's pinned Node.js and selects its `node` binary and licence | `-PnodeApp.node.version`, `-PnodeApp.node.source`, `-PnodeApp.node.pattern`, `-PbinaryRepo.repository` or `-PbinaryRepo.url` |
| `npmrc` | Writes the npm configuration for the registry | `-PnodeApp.npm.registry`, `-PnodeApp.npm.caFile`, `-PbinaryRepo.repo.npm` |
| `npmInstall`, `npmBuild`, `npmTest` | `npm ci`, your build script, your test script (`npmTest` is part of `check`) | `buildScript`, `testScript` |
| `npmInstallProduction<Target>` | `npm ci --omit=dev` for one target, away from the working tree | `installScripts` |
| `verifyNodeModules<Target>` | Fails if a dependency's install script left output for the build machine in another target's `node_modules` | `installScripts` |
| `nodeTrustedCertificates` | Bundles trusted CAs and the policy's into `security/trusted-cas.pem` | `security { trustedCertificates }` |

### RPMs, containers and publishing

| Task | What it does | Settings |
|---|---|---|
| `appRpm`, `appRpm<Target>` | Builds an RPM for each Linux target; part of `assemble` when enabled | `rpm { enabled, user, systemd }`, `appName`, `license`, the project's `description`; `-PjavaApp.rpm` |
| `appDockerfile` | Creates the project's root `Dockerfile` if missing and keeps the plugin's block of it up to date | |
| `appDockerContext` | Writes a container build context: the Dockerfile plus each Linux target's runtime and app, by CPU | `targets(…)`, `license` |
| `publish` | Gradle's own: publishes every target's app archive as `<app>` and runtime archive as `<app>-runtime`, under one version, with the target as the classifier. Stops for archives never started on this machine | `-PbinaryRepo.repository` or `-PbinaryRepo.url`, `-PbinaryRepo.repo.releases`, `-PbinaryRepo.repo.snapshots`, `-PjavaApp.allowUntestedPublish` |
| `aiSkills` | Writes the AI assistant skills and the build-check hook into the project, and adds the Claude Code plugin to `.claude/settings.json`; part of `assemble` | `-PjavaApp.aiSkills` |

In a deployment pipeline, the `app-smoke-test` plugin adds its own `appSmokeTest`, which smoke-tests
a published release declared as `smokeTest("com.example:orders-service:1.4.2")`, picking the archive
built for the machine it runs on. It takes `-PappSmokeTest.securityPolicy` to refuse an
archive that breaks the runtime policy.

## Documentation

| | |
|---|---|
| [What your app needs to do](docs/your-app.md) | The launcher, environment, configuration, what's in the archive, the SBOM |
| [Building for Linux, Mac and Windows](docs/targets.md) | Several targets from one build, and which ones are tested |
| [Java: modules, hardening and JDKs](docs/java.md) | jdeps and jlink, certificates and security policy, choosing a JDK distribution |
| [Packaging and deploying](docs/packaging.md) | tar.gz, zip, RPM and container images; Gradle distributions and launchers |
| [Performance tuning](docs/performance.md) | Java Flight Recorder on the embedded runtime, heap, GC and startup |
| [Keeping the runtime patched](docs/runtime-updates.md) | When runtime fixes come out, `checkRuntimeSupport` |
| [Renovate presets](docs/renovate.md) | Pull requests for runtime pin overrides and a base image you pin yourself |
| [Image layers and caching](docs/caching.md) | What makes images share layers, and how to choose module sets |
| [Testing the container image](docs/container-testing.md) | Testcontainers against the built image, and running on UBI micro |
| [Publishing](docs/publishing.md) | Maven repositories, signing, smoke-testing a published app |
| [Behind a corporate binary repository](docs/binary-repository.md) | One setting per machine; the repositories to create |
| [Reference](docs/reference.md) | Every setting and its default, every Gradle property |
| [Packaging standards](docs/packaging-standards.md) | The conventions each format follows, and the formats not built |
| [Ship the runtime with the app](docs/article.md) | The article: why runtimes belong in the release, and how these plugins do it |

### AI assistant skills

Skills that adopt the plugins in a project, find the JDK modules for jlink, upgrade dependencies and
runtimes, tune performance with Java Flight Recorder, and run a Java app on UBI micro, plus a hook that checks the build still configures after the agent edits a build file.

Every build installs them, as part of `assemble`, for **GitHub Copilot** (agent mode in VS Code,
Visual Studio and JetBrains IDEs, the Copilot app and CLI, the cloud agent), **Devin** and **Claude
Code**: the skills go in `.agents/skills/`, the hook in `.github/hooks/`, and the Claude Code plugin is
added to `.claude/settings.json`, so Claude Code offers it to everyone who opens the project. Files
are written only when they change; commit them. To turn this off, set `javaApp.aiSkills=false` (or
`nodeApp.aiSkills=false`) in `gradle.properties`.

Claude Code users in a project without the plugins can install the plugin themselves:

```
/plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
```

API documentation: https://fiftieshousewife.github.io/embedded-runtime-gradle-plugins/ (from each
plugin's KDoc; also in the javadoc jar of every publication).

Working on the plugins themselves: [AGENTS.md](AGENTS.md).

## Licence

[Apache License 2.0](LICENSE).
