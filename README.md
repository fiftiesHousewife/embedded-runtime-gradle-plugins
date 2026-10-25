# Embedded runtime Gradle plugins

Package a Java or Node.js application **with its own runtime** as one `.tar.gz`: your code, its
libraries, and a pinned JDK or Node.js. Servers and containers need nothing installed, and the
archive that passes CI is the archive that runs in production.

| Plugin | For | Embeds |
|---|---|---|
| `io.github.fiftieshousewife.java-app` | Java and Kotlin/JVM apps | A jlink-trimmed JDK from Temurin, Oracle, Corretto, Zulu, Liberica, Microsoft or SapMachine |
| `io.github.fiftieshousewife.node-app` | Node.js apps | The official Node.js runtime |

Each comes with a settings plugin (`….java-app.settings`, `….node-app.settings`) that sets up every
repository the build needs, so projects declare none of their own. A third plugin,
`io.github.fiftieshousewife.app-smoke-test`, checks a published app from any other build.

**Requirements:** Gradle 9.8 or newer. Java apps can target Java 11 to 25, whatever JDK Gradle
itself runs on.

## Quick start

### Java (or Kotlin)

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

For Kotlin, also apply `kotlin("jvm")`; nothing else changes.

### Node.js

```kotlin
// settings.gradle.kts
plugins {
    id("io.github.fiftieshousewife.node-app.settings") version "1.0.0"
}
```

```kotlin
// build.gradle.kts, next to package.json and package-lock.json
plugins {
    id("io.github.fiftieshousewife.node-app")
}

version = "2.1.0"

nodeApp {
    entryPoint = "server.mjs"
    buildScript = "build"      // optional npm script to run first, e.g. a TypeScript compile
}
```

A build with both Java and Node apps applies both settings plugins.

### Build and run

```bash
./gradlew build      # compile, test, package, then start the archive and check its health
./gradlew runApp     # run the packaged app in the foreground
```

The result is `build/distributions/orders-service-1.4.2-linux-x64.tar.gz`, named for the machine
you built on. `build` only passes if the packaged app started and reported healthy.

## Building for Linux, Mac and Windows

One build can make an archive for each OS and CPU you deploy to, and publish them all together:

```kotlin
javaApp {                                       // or nodeApp
    targets("linux-x64", "linux-aarch64", "windows-x64", "mac-aarch64")
}
```

or, without changing the build, `./gradlew build -PjavaApp.targets=linux-x64,windows-x64`. The
default is the machine you build on. A Mac or Windows laptop can build Linux archives with no
containers or WSL.

Only the archive for the machine you build on can be started there, so only that one is
smoke-tested. `publish` refuses archives that were never started unless you set
`allowUntestedPublish = true` (or pass `-PjavaApp.allowUntestedPublish=true`). The safest setup
publishes each target from a CI job running on it; [smoke-testing the published app](#smoke-testing-a-published-app)
on each target afterwards is the alternative.

## What your app needs to do

The launcher, `bin/<app>`, takes three commands:

| Command | Does |
|---|---|
| `run [args]` | Starts the app |
| `healthcheck` | Starts your app with `--healthcheck`; exit 0 means healthy |
| `version` | Prints `META-INF/app.json` |

So your app should read its port from `APP_PORT`, and exit 0 when started with `--healthcheck`
if the running instance is healthy. The apps in [`samples/`](samples) do this in a few lines.

The launcher also sets `APP_HOME`, `APP_ROOT`, `APP_CONF_DIR`, `APP_DATA_DIR` and `APP_LOG_DIR`,
and adds `APP_JAVA_OPTS` / `APP_NODE_OPTS` to the runtime's options.

Default configuration files go in `src/deploy/conf/` and ship as `conf.defaults/`. Read `conf/`
first and fall back to `conf.defaults/`, so an upgrade never overwrites a deployed file.

### Inside the archive

```
orders-service-1.4.2/
  bin/orders-service      # .bat on Windows targets
  runtime/                # the embedded JDK or Node.js
  lib/                    # Java: your jar and its dependencies
  app/                    # Node: your code, package.json and production node_modules
  conf.defaults/
  META-INF/app.json       # name, version, target, runtime, git commit
  META-INF/sbom.cdx.json  # CycloneDX SBOM, including the runtime
```

Archives are reproducible: rebuilding the same commit gives a byte-identical file.

## Which JDK modules go in

The embedded runtime holds only the JDK modules your app uses. By default these are what `jdeps`
finds in your code and its dependencies. jdeps can't see modules loaded by name at runtime, such as
`jdk.crypto.ec` for TLS, `java.naming` for JNDI or LDAP, or `jdk.localedata` for other locales.
If one is missing, the smoke test fails and says so. Then run:

```bash
./gradlew jlinkFindModules
```

It starts the app, adds the module holding each JDK class it fails to load, and tries again until
the app is healthy, then prints what to add:

```kotlin
javaApp { additionalModules.addAll("jdk.crypto.ec") }
```

To choose the modules yourself instead, set `jlinkModules = listOf(...)`.

## Choosing a JDK

```properties
# gradle.properties
javaApp.jdk.distribution=corretto
```

| Distribution | Exact version looks like |
|---|---|
| `temurin` (default) | `21.0.13+9` |
| `oracle` | `25.0.3` |
| `corretto` | `21.0.13.0.10` |
| `zulu` | `21.54.107-ca-jdk21.0.13` |
| `liberica` | `21.0.13+11` |
| `microsoft` | `21.0.12` |
| `sapmachine` | `21.0.13` |

Each plugin release pins an exact, tested version of every distribution and Java release, and the
same JDK compiles your code and runs inside the archive. To take a security patch before the next
plugin release, override the pin:

```properties
javaApp.jdk.21=21.0.13+9
```

A version in the wrong form for its distribution is rejected before anything is downloaded.
SapMachine 25 has no Intel Mac (`mac-x64`) build.

> **Oracle licensing.** Whether you may redistribute an Oracle runtime inside your archive is
> decided by Oracle's licence, not by these plugins. Recent releases under the NFTC allow it;
> OTN-licensed releases do not. The other distributions are OpenJDK builds under GPLv2 with the
> Classpath Exception.

To download from a mirror instead, see `javaApp.jdk.url` under [Configuration](#configuration).

### JDKs already on disk

To use JDKs you already have instead of downloading them, point at a shared directory of archives,
laid out like the [Artifactory runtimes repository](#repositories-to-create):

```properties
javaApp.jdk.localPath=/shared/jdks     # /shared/jdks/temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz
```

or configure one JDK at a time, as an installed directory or an archive:

```properties
javaApp.jdk.21.linux-x64.path=/usr/lib/jvm/jdk-21
javaApp.jdk.21.windows-x64.path=/shared/jdks/jdk-21-windows.zip
```

A JDK configured this way wins over every other source for that release and target, so it also
covers JDKs installed from your own RPMs. The JDK used to run `jlink` (the one for the machine you
build on) and each target's JDK must be the same exact version. Gradle compiles with an archive
configured this way directly; for an installed directory, also list it in
`org.gradle.java.installations.paths`.

## Behind a corporate Artifactory

Set this once per machine or CI agent in `~/.gradle/gradle.properties`, never in a project:

```properties
artifactory.url=https://artifactory.example.com/artifactory
artifactoryUsername=build-user
artifactoryPassword=<identity or reference token>
```

Every download then goes through Artifactory: libraries, Gradle plugins, JDKs, Node.js and npm
packages. On CI, pass the credentials as `ORG_GRADLE_PROJECT_artifactoryUsername` and
`ORG_GRADLE_PROJECT_artifactoryPassword`.

Also copy [`gradle/init.d/embedded-runtime-artifactory.init.gradle.kts`](gradle/init.d) into
`~/.gradle/init.d/`, so Gradle can download the settings plugins themselves from Artifactory.

### Repositories to create

| Repository | Type | Holds |
|---|---|---|
| `maven-virtual` | Maven virtual | Maven Central, plus your local repositories |
| `gradle-plugins-virtual` | Maven virtual | `https://plugins.gradle.org/m2/` (which also needs `plugins-artifacts.gradle.org`) |
| `npm-virtual` | npm virtual | registry.npmjs.org |
| `runtimes` | Generic local | Approved JDK and Node.js archives |
| `libs-release-local`, `libs-snapshot-local` | Maven local | Your published apps |

JDK and Node.js archives are read from `runtimes` in this layout:

```
runtimes/temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz
runtimes/nodejs/node/24.18.1/node-24.18.1-linux-x64.tar.gz
```

[`deploy/curate-runtimes.sh`](deploy/curate-runtimes.sh) downloads a Temurin or Node.js release,
checks it against the vendor's SHA-256 and uploads it there. An existing tree with a different
layout can be used as it is, by giving its pattern:

```properties
javaApp.jdk.artifactoryPattern=jdks/[organisation]/[classifier]/[revision].[ext]
```

A pattern can use `[organisation]` (the distribution, e.g. `temurin`, or `nodejs`), `[module]` and
`[artifact]` (both `jdk` or `node`), `[revision]` (the version, with `+` written as `_`),
`[classifier]` (the target, e.g. `linux-x64`) and `[ext]` (`tar.gz`, or `zip` on Windows).

## Publishing

`./gradlew publish` uploads every target's archive and SBOM to your Maven repository, under one
version, with the target as the classifier:

```
com/example/orders-service/1.4.2/orders-service-1.4.2-linux-x64.tar.gz
com/example/orders-service/1.4.2/orders-service-1.4.2-linux-x64-cyclonedx.json
com/example/orders-service/1.4.2/orders-service-1.4.2-windows-x64.tar.gz
…
```

With Artifactory configured, releases go to `libs-release-local` and `-SNAPSHOT` versions to
`libs-snapshot-local`. To publish under other coordinates:

```kotlin
publishing.publications.named<MavenPublication>("javaApp") {   // or "nodeApp"
    groupId = "com.acme.apps"
    artifactId = "orders"
}
```

Pass `-PsigningKey=…` and `-PsigningPassword=…` to sign what you publish.

## Deploying

**To a server**, with [`deploy/install.sh`](deploy/install.sh), which needs no root:

```bash
deploy/install.sh install --coordinates com.example:orders-service:1.4.2 --app-root /srv/orders \
    --repo-url https://artifactory.example.com/artifactory/libs-release-local
deploy/install.sh rollback --app-root /srv/orders
deploy/install.sh status   --app-root /srv/orders
```

It keeps each release in its own directory under `releases/` and points `current` at the active
one. It creates `conf/` from `conf.defaults/` once and never overwrites it, starts the app (as a
systemd user service where available, otherwise in the background), waits for its health check, and
rolls back automatically if that fails.
Per-server settings such as `APP_PORT` go in `conf/env`.

**As a container image:** `./gradlew appDockerContext` writes `build/docker-context/` with a
Dockerfile and a Linux archive under `app/`, ready for `docker build`. It uses your project's own
`Dockerfile` (or `src/deploy/docker/Dockerfile`, or whatever `dockerfile` is set to) if there is
one, and otherwise generates one whose base image needs only glibc and a shell. It needs a Linux
target, and prefers the one matching your machine's CPU.

## Smoke-testing a published app

Any build can check a published app, for example a deployment pipeline before it rolls a release
out, with the same smoke test the app's own build runs:

```kotlin
// build.gradle.kts of the checking build
plugins {
    id("io.github.fiftieshousewife.app-smoke-test") version "1.0.0"
}
repositories { maven("https://artifactory.example.com/artifactory/libs-release-local") }
dependencies { smokeTest("com.example:orders-service:1.4.2") }
```

`./gradlew appSmokeTest` downloads the archive built for the machine it runs on, starts it and
waits for its health check. Run it on each target you publish for.

## Reference

### `javaApp {}` and `nodeApp {}`

```kotlin
javaApp {                                       // nodeApp {} has the same first seven settings
    appName = "orders-service"                  // launcher and archive name; default: project name
    targets("linux-x64", "windows-x64")         // default: -PjavaApp.targets, else this machine
    dockerfile = file("docker/Dockerfile")      // default: Dockerfile, else src/deploy/docker/Dockerfile
    configDefaults = file("src/deploy/conf")    // shipped as conf.defaults/
    smokeTestEnabled = true                     // start the archive during `check`
    smokeTestTimeout = Duration.ofSeconds(60)
    allowUntestedPublish = false

    javaVersion = 21                            // 11 to 25
    jdkVersion = "21.0.13+9"                    // default: the plugin's pin
    additionalModules = listOf("jdk.crypto.ec") // added to what jdeps finds
    jlinkModules = listOf("java.base")          // instead: exactly these modules
    jlinkOptions = listOf("--strip-debug", "--no-man-pages", "--no-header-files", "--compress=zip-6")
    jvmArgs = listOf("-XX:MaxRAMPercentage=75") // before $APP_JAVA_OPTS
}

nodeApp {
    nodeVersion = "24.18.1"                     // default: the plugin's pin
    entryPoint = "main.js"                      // under app/; started by `run`
    appFiles = files("dist")                    // default: src/ (minus src/deploy/), or dist/ with buildScript
    buildScript = "build"                       // npm script run before packaging
    testScript = "test"                         // npm script run by `check`, if package.json has it
    ignoreInstallScripts = true                 // npm --ignore-scripts for production dependencies
    portableInstallScripts = listOf("esbuild")  // vetted packages that don't block cross builds
    nodeArgs = listOf("--enable-source-maps")   // before $APP_NODE_OPTS
}
```

### Tasks

| Task | Does |
|---|---|
| `appTar` | Builds every target's archive (`appTarLinuxX64` and so on for one); part of `assemble` |
| `appSmokeTest` | Starts this machine's archive and waits for its health check; part of `check` |
| `runApp` | Runs this machine's packaged app; pass arguments with `-Pargs="…"` |
| `appDockerContext` | Writes a container build context |
| `jdeps` | Java: lists the JDK modules the code uses |
| `jlinkFindModules` | Java: finds the modules jdeps can't see, by running the app |
| `jlinkLinuxX64`, … | Java: builds one target's trimmed runtime |
| `npmInstall`, `npmBuild`, `npmTest` | Node: `npm ci`, your build script, your test script |

### Configuration

Gradle properties, set in `gradle.properties`, `~/.gradle/gradle.properties` or with `-P`:

| Property | Default | |
|---|---|---|
| `javaApp.jdk.distribution` | `temurin` | JDK vendor |
| `javaApp.jdk.<feature>` | the plugin's pin | Exact JDK version, e.g. `javaApp.jdk.21=21.0.13+9` |
| `javaApp.jdk.url`, `javaApp.jdk.pattern` | the vendor's site | Download JDKs from a mirror |
| `javaApp.jdk.localPath`, `javaApp.jdk.localPattern` | | Read JDK archives from a shared directory |
| `javaApp.jdk.<feature>.<target>.path` | | One JDK, as a directory or archive, e.g. `javaApp.jdk.21.linux-x64.path` |
| `nodeApp.node.version` | the plugin's pin | Exact Node.js version |
| `nodeApp.node.url`, `nodeApp.node.pattern` | nodejs.org/dist | Download Node.js from a mirror |
| `nodeApp.npm.registry` | registry.npmjs.org | npm registry when not using Artifactory |
| `nodeApp.npm.caFile` | | PEM file of corporate CAs for npm |
| `javaApp.targets`, `nodeApp.targets` | this machine | Comma-separated targets to build |
| `javaApp.allowUntestedPublish`, `nodeApp.allowUntestedPublish` | `false` | Allow publishing an archive that was never started |
| `artifactory.url` | | Download everything through this Artifactory |
| `artifactory.repo.maven` | `maven-virtual` | |
| `artifactory.repo.gradlePlugins` | `gradle-plugins-virtual` | |
| `artifactory.repo.npm` | `api/npm/npm-virtual` | |
| `artifactory.repo.runtimes` | `runtimes` | |
| `artifactory.repo.releases`, `artifactory.repo.snapshots` | `libs-release-local`, `libs-snapshot-local` | |
| `javaApp.jdk.artifactoryPattern`, `nodeApp.node.artifactoryPattern` | the layout above | Layout of an existing runtimes tree |
| `artifactory.allowInsecureProtocol` | `false` | Allow `http://` (test instances only) |

## Licence

[Apache License 2.0](LICENSE).
