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
import java.time.Duration

plugins {
    id("io.github.fiftieshousewife.java-app")
}

group = "com.example"
version = "1.4.2"

application {
    mainClass = "com.example.orders.Main"
}

javaApp {
    appName = "orders-service"                 // launcher and archive name; default: the project name

    // Target platforms: one archive each. A Mac or Windows machine builds the Linux ones directly,
    // with no containers. Only this machine's archive can be started, so only it is smoke-tested.
    targets("linux-x64", "linux-aarch64", "windows-x64")
    // Archives: zip for Windows, tar.gz for Linux and macOS, unless archiveFormat("…") picks one for all
    allowUntestedPublish = true                // publish the targets this machine couldn't start

    // The runtime: an exact JDK, compiled with and embedded. The vendor is a Gradle property
    // (javaApp.jdk.distribution=temurin, oracle, corretto, zulu, liberica, microsoft, sapmachine).
    javaVersion = 21                           // 11 to 27
    jdkVersion = "21.0.12.1+1"                 // default: the plugin's pin for javaVersion

    // jlink links only the modules jdeps finds, plus these, which code loads by name at runtime
    // (`./gradlew jlinkFindModules` finds them by running the app).
    additionalModules = listOf("jdk.crypto.ec", "java.naming", "jdk.localedata")

    // How the JVM starts. By default it gets 75% of the machine's or container's memory, exits on
    // OutOfMemoryError and leaves a heap dump and crash logs in $APP_LOG_DIR; jvmArgs come after
    // these. With runtimeOptions("locked") the launcher refuses to start while JAVA_TOOL_OPTIONS,
    // APP_JAVA_OPTS or a jvm.options file would add options of their own.
    runtimeDefaults {
        garbageCollector = "G1"                // unset by default: the JVM chooses
        disableAttach = true                   // no jcmd or agents attaching to the running service
    }
    jvmArgs = listOf("-Xss512k")
    runtimeOptions("locked")

    // CAs and encryption, applied to every target's runtime after jlink.
    security {
        trustedCertificates.from("security/certs")                    // PEM or DER, added to cacerts
        trustedCertificate(uri("https://pki.example.com/root-ca.pem"), sha256 = "9f86d081884c7d65…")
        disabledTlsAlgorithms.addAll(                                 // added to the JDK's own list
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        )
        properties.put("networkaddress.cache.ttl", "30")              // any java.security property
        propertiesFiles.from("security/java.security")                // or a whole policy file
        lockProperties = true                                         // no -Djava.security.properties
    }

    // Packaging beyond the archive.
    configDefaults = file("src/deploy/conf")   // shipped as conf.defaults/, the fallback for conf/
    rpm {
        enabled = true                         // an RPM per Linux target, installed under /opt
        vendor = "Example Ltd"
        license = "LicenseRef-Proprietary"
        systemd = true                         // a hardened systemd unit, run as its own user
    }

    smokeTestTimeout = Duration.ofSeconds(90)  // how long the started archive may take to get healthy
}
```

Put the machine's settings in `gradle.properties` or `~/.gradle/gradle.properties`, or pass them as
`ORG_GRADLE_PROJECT_…` variables in CI:

```properties
# the JDK vendor
javaApp.jdk.distribution=temurin
# the repository settings.gradle.kts already declares: its server and credentials are used for
# runtimes, npm and publishing (see "Behind a corporate binary repository")
binaryRepo.repository=artifactory
# the organisation's runtime policy
javaApp.securityPolicyArtifact=com.example.platform:java-runtime-policy:7
```

`./gradlew build` then gives three archives in `build/distributions/` and two RPMs. `publish` uploads
them under one version, with the target as the classifier, and the RPMs to the YUM repository. The
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
    nodeVersion = "24.22.1"                    // default: the plugin's pin

    targets("linux-x64", "linux-aarch64")
    ignoreInstallScripts = true                // npm --ignore-scripts for production dependencies
    portableInstallScripts = listOf("esbuild") // packages whose install scripts don't block cross builds

    nodeArgs = listOf("--enable-source-maps", "--max-old-space-size=512")
    runtimeOptions("locked")                   // also refuses NODE_OPTIONS and NODE_TLS_REJECT_UNAUTHORIZED

    // CAs and encryption: the CAs go into security/trusted-cas.pem, which the launcher passes to
    // Node as NODE_EXTRA_CA_CERTS; the TLS settings become node options.
    security {
        trustedCertificates.from("security/certs")
        tlsMinVersion = "1.3"
        tlsCipherList = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"
    }
}
```

See [What your app needs to do](docs/your-app.md#nodejs-trusted-cas-and-tls) and
[Behind a corporate binary repository](docs/binary-repository.md) for npm registries and CAs.

### How big the archive is

Measured for linux-x64 against Temurin 21.0.12.1+1, on 25 October 2026, in megabytes of a million
bytes:

| | Compressed | Unpacked |
|---|---|---|
| Full Temurin 21 JDK, for comparison | 207.5 MB | 353 MB |
| Temurin 21 JRE, for comparison | 52.1 MB | |
| **Spring Boot 4.1 web service with Actuator: the whole archive** | **58.9 MB** | **85.7 MB** |
| of which the trimmed runtime (13 modules found by jdeps, 18 with what they need) | 39.3 MB | 63.9 MB |
| of which Spring Boot, Tomcat, Jackson and the service (50 jars) | 19.7 MB | 21.8 MB |
| **Plain HTTP service on the JDK's own server: the whole archive** | **21.0 MB** | **40.9 MB** |
| of which the trimmed runtime (`java.base`, `jdk.httpserver`; 26 MB of it is the JVM itself) | | 40.9 MB |

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
| `appArchive` | Builds every target's archive into `build/distributions/`; part of `assemble` | `targets(…)`, `archiveFormat(…)`, `appName`; `-PjavaApp.targets`, `-PjavaApp.archiveFormat` |
| `<target>DistTar`, `<target>DistZip` | One target's archive, `<app>-<version>-<target>.tar.gz` (or `.tar`, `.zip`) | as above; `configDefaults` (shipped as `conf.defaults/`) |
| `installDist` | Lays out this machine's app with its runtime in `build/install/<app>` | as above |
| `install<Target>Dist` | The same for one target, e.g. `installLinuxX64Dist` | as above |
| `startScripts` | Java: writes `bin/<app>` and `bin/<app>.bat`, which start the embedded runtime | `runtimeDefaults { … }`, `jvmArgs`, `application { applicationDefaultJvmArgs }`, `runtimeOptions("locked")`; `-PjavaApp.runtimeOptions` |
| `appLaunchers<Target>` | Node: writes the target's launcher | `entryPoint`, `nodeArgs`, `security { tlsMinVersion, tlsCipherList }`, `runtimeOptions("locked")`; `-PnodeApp.runtimeOptions` |
| `appManifest<Target>` | Writes `META-INF/app.json`: name, version, target, runtime, git commit | `appName` |
| `appSbom<Target>` | Writes `META-INF/sbom.cdx.json`, a CycloneDX SBOM including the runtime; skipped when you supply your own | `sbom = …` (your own SBOM instead) |
| `appVerifyPolicy<Target>` | Checks the assembled app against the organisation's runtime policy; every archive depends on it, and it is skipped when no policy is set | `-PjavaApp.securityPolicy` or `-PjavaApp.securityPolicyArtifact` (`nodeApp.…` for Node) |

### Running and checking it

| Task | What it does | Settings |
|---|---|---|
| `appSmokeTest` | Unpacks this machine's archive, checks `version`, starts the app on a free port and waits for its health check; part of `check` | `smokeTestEnabled`, `smokeTestTimeout` |
| `runApp` | Runs this machine's packaged app in the foreground | `-Pargs="…"` (arguments for the app) |
| `checkRuntimeSupport` | Fails if the embedded runtime's release line has reached end of life; warns if it soon will or a newer patch is out. Run it on a schedule; not part of `check` | `-PjavaApp.runtimeSupportUrl`, `-PjavaApp.endOfLifeWarningDays`, `-PjavaApp.allowEndOfLife` (`nodeApp.…` for Node) |
| `appVerifyPublishable` | Stops `publish` for archives that were never started on this machine | `allowUntestedPublish`; `-PjavaApp.allowUntestedPublish` |

### Java runtime

| Task | What it does | Settings |
|---|---|---|
| `downloadJdk<Target>` | With `javaApp.jdk.yumRepo`: finds the JDK package for the pin and the target's CPU in the YUM repository's metadata, downloads it and checks its SHA-256 | `-PjavaApp.jdk.yumRepo`, `-PjavaApp.jdk.yumPackage`, `-PjavaApp.jdk.revisionFormat` |
| `extractJdk<Target>`, `extractJmods<Target>` | Downloads and unpacks the target's pinned JDK (and, from Java 24, its separate jmods) | `javaVersion`, `jdkVersion`; `-PjavaApp.jdk.distribution`, `-PjavaApp.jdk.<feature>`, `-PjavaApp.jdk.url`, `-PjavaApp.jdk.localPath`, `-PjavaApp.jdk.<feature>.<target>.path`, `-PbinaryRepo.repository` or `-PbinaryRepo.url` |
| `jdeps` | Lists the JDK modules the code refers to | |
| `jlinkFindModules` | Runs the app, adding each JDK module it turns out to need, until it is healthy | `smokeTestTimeout` |
| `jlink<Target>` | Links the target's trimmed runtime | `additionalModules`, `jlinkModules`, `sharedModuleSets`, `moduleSets`, `jlinkOptions` |
| `customiseRuntime<Target>` | Applies trusted certificates, security properties, the overlay and the runtime policy to it | `security { … }`, `runtimeOverlay`; `-PjavaApp.securityPolicy[Artifact]` |

### Node.js

| Task | What it does | Settings |
|---|---|---|
| `extractNode<Target>`, `nodeRuntime<Target>` | Downloads the target's pinned Node.js and selects its `node` binary and licence | `nodeVersion`; `-PnodeApp.node.version`, `-PnodeApp.node.url`, `-PbinaryRepo.repository` or `-PbinaryRepo.url` |
| `npmrc` | Writes the npm configuration for the registry | `-PnodeApp.npm.registry`, `-PnodeApp.npm.caFile`, `-PbinaryRepo.repo.npm` |
| `npmInstall`, `npmBuild`, `npmTest` | `npm ci`, your build script, your test script (`npmTest` is part of `check`) | `buildScript`, `testScript` |
| `npmInstallProduction<Target>` | `npm ci --omit=dev` for one target, away from the working tree | `ignoreInstallScripts`, `portableInstallScripts` |
| `verifyNodeModules<Target>` | Fails if a dependency's install script left output for the build machine in another target's `node_modules` | `ignoreInstallScripts`, `portableInstallScripts` |
| `nodeTrustedCertificates` | Bundles trusted CAs and the policy's into `security/trusted-cas.pem` | `security { trustedCertificates }` |

### RPMs, containers and publishing

| Task | What it does | Settings |
|---|---|---|
| `appRpm`, `appRpm<Target>` | Builds an RPM for each Linux target; part of `assemble` when enabled | `rpm { enabled, packageName, release, summary, license, vendor, url, user, systemd }`; `-PjavaApp.rpm` |
| `appPublishRpm` | Uploads the RPMs to a YUM repository; part of `publish` when one is set | `rpm { repositoryUrl }`; `-PbinaryRepo.repo.rpm`; the binary repository's login |
| `appDockerfile` | Creates the project's `Dockerfile` if missing and keeps the plugin's block of it up to date | `dockerfile` |
| `appDockerContext` | Writes a container build context: the Dockerfile plus each Linux archive, by CPU | `dockerfile`, `targets(…)`, `license`, `sourceUrl` |
| `publish` | Gradle's own: publishes every target's archive under one version, with the target as the classifier | `-PbinaryRepo.repository` or `-PbinaryRepo.url`, `-PbinaryRepo.repo.releases`, `-PbinaryRepo.repo.snapshots` |
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
