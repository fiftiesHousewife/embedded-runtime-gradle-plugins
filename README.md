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
| `io.github.fiftieshousewife.app-deploy` | A deployment build installing a published app | |

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

## Documentation

| | |
|---|---|
| [What your app needs to do](docs/your-app.md) | The launcher, environment, configuration, what's in the archive, the SBOM |
| [Building for Linux, Mac and Windows](docs/targets.md) | Several targets from one build, and which ones are tested |
| [Java: modules, hardening and JDKs](docs/java.md) | jdeps and jlink, certificates and security policy, choosing a JDK distribution |
| [Packaging and deploying](docs/packaging.md) | tar.gz, tar, zip, RPM and container images; `appInstall` and rollback |
| [Publishing](docs/publishing.md) | Maven repositories, signing, smoke-testing a published app |
| [Behind a corporate Artifactory](docs/artifactory.md) | One setting per machine; the repositories to create |
| [Reference](docs/reference.md) | Every setting, task and Gradle property |
| [Packaging standards](docs/packaging-standards.md) | The conventions each format follows, and the formats not built |
| [Ship the runtime with the app](docs/article.md) | The article: why runtimes belong in the release, and how these plugins do it |

### Claude Code skills

Skills that adopt the plugins in a project, find the JDK modules for jlink, and upgrade
dependencies and runtimes (`/embedded-runtime:adopt`, `/embedded-runtime:jlink-modules`,
`/embedded-runtime:upgrade`):

```
/plugin install embedded-runtime --marketplace fiftiesHousewife/embedded-runtime-gradle-plugins
```

Working on the plugins themselves: [AGENTS.md](AGENTS.md).

## Licence

[Apache License 2.0](LICENSE).
