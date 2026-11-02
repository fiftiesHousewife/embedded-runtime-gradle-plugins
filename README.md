# Embedded runtime Gradle plugins

[![CI](https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins/actions/workflows/ci.yml/badge.svg)](https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%E2%80%9327-orange.svg)](docs/java.md)
[![Gradle](https://img.shields.io/badge/Gradle-9.8%2B-02303A.svg)](https://gradle.org)

Package a Java or Node.js application **with its own runtime**: your code, its libraries and a pinned,
trimmed JDK or Node.js, as three archives per target. Servers and containers need nothing installed, what
passes CI is what runs in production, and a release that changes only your code adds a few kilobytes of new
binary to your repository.

## Recommendations

What we would tell a team starting today, each from something built and measured here. The
[article](docs/article.md#summary-of-recommendations) gives the evidence for each.

**Ship**

1. **Make the runtime part of the release.** Three archives under one version, `<app>`, `<app>-libs` and
   `<app>-runtime`, instead of a shared JDK that CI never tested. ([Packaging](docs/packaging.md))
2. **Compile with the JDK you ship.** One exact pin serves both, so they can't drift. ([Java](docs/java.md#choosing-a-jdk))
3. **Let a real start decide.** `build` starts the finished archive and `publish` refuses one that was never
   started. jdeps can't see modules loaded by name, so run `jlinkFindModules`, and assert behaviour that fails
   silently, such as a missing `jdk.localedata`. ([Java](docs/java.md#which-jdk-modules-go-in))
4. **Reach nothing but your binary repository.** JDKs, Node.js, npm, RPMs, the Gradle wrapper and base images,
   mirrored and pinned. Set the base images yourself: their defaults still name Red Hat's registry.
   ([Binary repository](docs/binary-repository.md))

**Build images**

5. **Describe images; don't write RPM stages.** `images { register("ubi-micro") { from(ubiMicro) } }` on Java,
   UBI minimal on Node.js. Commit `rpms.lock.yaml`. Nobody on the team needs to meet `microdnf`, and the image
   build needs no repository and no emulation. ([Containers](docs/container-testing.md#on-ubi-micro))
6. **Test the image the way its judge reads it.** A hardened start, the image's real `User`, `/licenses` as
   regular files, `java -version` through the dynamic loader. Each failure we found was somewhere a test read
   something a platform wouldn't. ([What goes wrong](docs/container-testing.md#what-goes-wrong-in-containers))

**Do not repeat yourself**

7. **Order parts from most reused to least, and split where reuse differs.** The same stack in an image and on
   a server: base, runtime, libraries, app. ([Caching](docs/caching.md#do-not-repeat-yourself))
8. **Assert sameness; storage won't complain.** Compare layer digests in a test and publish with
   `-PjavaApp.previousRelease=latest.release`. ([Tips](docs/caching.md#tips-for-caching-more))
9. **Publish from Linux or macOS agents on one JDK, and deploy by checksum.** Gradle's own `publish` sends
   every byte for Artifactory to discard. ([Publishing](docs/publishing.md#publishing-the-same-runtime-again))
10. **Keep four to six module sets, and stop short of the compiler.** Choose them from your own `MODULES=`
    lines. ([Module sets](docs/caching.md#shared-module-sets))

**Run**

11. **Prefer `httpGet` probes.** An exec probe is a second JVM, inside the pod's limit, carrying the server's
    options. ([Probes](docs/packaging.md#kubernetes-probes))
12. **Size memory from `memory.peak`, with the request equal to the limit.** The 25% the default heap share leaves
    is what you are really sizing. Name the collector. ([Capacity](docs/capacity.md#align-memory-with-the-heap))
13. **Measure CPU time per request, not latency.** Warm up for tens of thousands of requests, read the cgroup, and
    compare only runs from a quiet machine of one type. ([Capacity](docs/capacity.md#test-cpu-repeatably))
14. **Keep it moving.** Take each quarterly patch within weeks, run `checkRuntimeSupport` on a schedule, and let
    measurements open the chart's pull request. ([Patching](docs/runtime-updates.md), [Capacity](docs/capacity.md#keep-the-chart-current))

**Govern**

15. **Check artefacts, not intentions.** A machine-wide runtime policy, checked before any archive exists and again
    before rollout, with the wire enforced at the network boundary, since code in the JVM can step around any
    setting. ([Policy](docs/java.md#an-organisation-wide-runtime-policy))
16. **Sign in CI and verify before starting.** Keyless Sigstore, with the signer's identity pinned in the
    deployment pipeline. ([Publishing](docs/publishing.md#only-archives-your-release-workflow-signed))

## Quick start

| Plugin | For | Embeds |
|---|---|---|
| `io.github.fiftieshousewife.java-app` | Java and Kotlin/JVM apps | A jlink-trimmed JDK from Oracle, Temurin, Corretto, Zulu, Liberica, Microsoft or SapMachine |
| `io.github.fiftieshousewife.node-app` | Node.js apps | The official Node.js runtime |
| `io.github.fiftieshousewife.app-smoke-test` | Any build checking a published app | |

Requires Gradle 9.8 or newer. Java apps can target Java 11 to 27, whatever JDK Gradle itself runs on.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()                         // or your binary repository
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

javaApp { javaVersion = 21 }
```

```bash
./gradlew build      # compile, test, package, then start the archives and check their health
./gradlew runApp     # run the packaged app in the foreground
```

Your app needs to read `APP_PORT` and answer `--healthcheck`; see [What your app needs to do](docs/your-app.md).
For Kotlin, also apply `kotlin("jvm")`. For several targets, hardening, an RPM, images and Node.js, see
[Getting started](docs/getting-started.md).

## What you get

The Spring Boot sample, on Oracle's Java 25, measured on 2 November 2026 (a megabyte is a million bytes):

| | linux-x64 | |
|---|---|---|
| App archive: launcher, your jar, SBOM | 8 KB | New every release |
| Libraries archive: Spring Boot, Tomcat, Jackson | 19.6 MB | The same bytes on every CPU, until a dependency moves |
| Runtime archive: the `framework` module set | 40.1 MB, 63 MB unpacked | The same bytes until the JDK, modules or policy move |
| Image on UBI micro (linux-aarch64) | 117 MB | Against 196 MB on UBI minimal |

The [samples](samples) are a plain Java HTTP service, a Spring Boot service, a Node.js service, a service built
and run as an image when Docker is available, and a deployment check that verifies a release's Sigstore signature
before starting it.

## Documentation

| Start here | |
|---|---|
| [Getting started](docs/getting-started.md) | A complete Java service and a Node.js service, every block explained |
| [What your app needs to do](docs/your-app.md) | The launcher, environment, configuration, what's in the archives, the SBOM |
| [Ship the runtime with the app](docs/article.md) | The paper: why runtimes belong in the release, the evidence, and what's still open |

| Build and ship | |
|---|---|
| [Building for Linux, Mac and Windows](docs/targets.md) | Several targets from one build, and which are tested |
| [Java: modules, hardening and JDKs](docs/java.md) | jdeps and jlink, certificates and policy, choosing a JDK |
| [Packaging and deploying](docs/packaging.md) | Archives, RPMs and images, probes and stopping |
| [Testing the container image](docs/container-testing.md) | Testcontainers, UBI micro, `images {}`, OpenShift, and what goes wrong |
| [Publishing](docs/publishing.md) | Maven repositories, deploying by checksum, signing, smoke-testing a release |
| [Behind a corporate binary repository](docs/binary-repository.md) | One setting per machine, and the repositories to create |

| Optimise and operate | |
|---|---|
| [Image layers and caching](docs/caching.md) | Do Not Repeat Yourself: what makes bytes repeat, how to test it, choosing module sets |
| [Sizing pods from measurements](docs/capacity.md) | Memory aligned with the heap, CPU measured repeatably, keeping a chart current |
| [Performance tuning](docs/performance.md) | Java Flight Recorder on the embedded runtime, heap, GC and startup |
| [Keeping the runtime patched](docs/runtime-updates.md) | When fixes come out, and `checkRuntimeSupport` |
| [Renovate presets](docs/renovate.md) | Pull requests for runtime pin overrides and a base image you pin yourself |

| Look up | |
|---|---|
| [Reference](docs/reference.md) | Every setting, task and Gradle property |
| [Packaging standards](docs/packaging-standards.md) | The conventions each format follows, and the formats not built |

### AI assistant skills

Every build installs skills that adopt the plugins, find the JDK modules for jlink, upgrade dependencies and
runtimes, tune performance and run a Java app on UBI micro, plus a hook that checks the build still configures
after an agent edits a build file. They go where **GitHub Copilot**, **Devin** and **Claude Code** find them, as
part of `assemble`, and only when they change, so commit them. To turn this off, set `javaApp.aiSkills=false` (or
`nodeApp.aiSkills=false`).

Claude Code users in a project without the plugins can install the plugin themselves:

```
/plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
```

API documentation: https://fiftieshousewife.github.io/embedded-runtime-gradle-plugins/ (from each plugin's KDoc;
also in the javadoc jar of every publication). Working on the plugins themselves: [AGENTS.md](AGENTS.md).

## Licence

[Apache License 2.0](LICENSE).
