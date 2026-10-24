# Platform bundle plugins

Gradle plugins that package a Java or Node.js application as a self-contained `.tar.gz` — your
code, its libraries, and a pinned language runtime — so the artefact that passes CI is the artefact
that runs in production, with no JDK or Node installed on the target machine.

| Plugin | Applied in | Produces |
|---|---|---|
| `io.github.fiftieshousewife.platform.settings` | `settings.gradle.kts` | Repositories, JDK toolchain provisioning, plugin versions |
| `io.github.fiftieshousewife.platform.java-bundle` | `build.gradle.kts` | Java app + a jlink-trimmed JDK runtime |
| `io.github.fiftieshousewife.platform.node-bundle` | `build.gradle.kts` | Node.js app + the official Node runtime |

**Requirements.** Gradle 9.8 or newer, running on Java 17–27 (Gradle's own requirement). A bundled
application may target **Java 11 to 25** independently of that — the compile JDK is provisioned by
toolchain, not taken from your `PATH`.

---

## Quick start

### A Java service

```kotlin
// settings.gradle.kts
plugins {
    id("io.github.fiftieshousewife.platform.settings") version "1.0.0"
}
rootProject.name = "orders-service"
```

```kotlin
// build.gradle.kts
plugins {
    id("io.github.fiftieshousewife.platform.java-bundle")   // no version: the settings plugin pins it
}

group = "com.example"
version = "1.4.2"

application { mainClass = "com.example.orders.Main" }

bundle {
    javaVersion = 21
    // Keep this explicit and reviewed: jdeps cannot see modules loaded reflectively, such as
    // jdk.crypto.ec (TLS), java.naming (LDAP/JNDI) or jdk.localedata.
    jlinkModules = listOf("java.base", "java.sql", "java.naming", "jdk.crypto.ec")
    jvmArgs = listOf("-XX:MaxRAMPercentage=75")
}
```

```bash
./gradlew build          # compiles, bundles, extracts and smoke-tests the archive
./gradlew runBundle      # runs the assembled bundle in the foreground
```

The result is `build/distributions/orders-service-1.4.2-linux-x64.tar.gz`.

### A Node.js service

```kotlin
// build.gradle.kts
plugins {
    id("io.github.fiftieshousewife.platform.node-bundle")
}

version = "2.1.0"

bundle {
    entryPoint = "server.mjs"
    buildScript = "build"          // optional npm script run before packaging
    nodeArgs = listOf("--enable-source-maps")
}
```

### What you get inside the archive

```
orders-service-1.4.2/
  bin/orders-service       # or orders-service.bat on Windows targets
  runtime/                 # jlink image, or the Node runtime
  lib/                     # the application jar and its dependencies (Java)
  app/                     # your JS (Node)
  conf.defaults/           # copied from src/bundle/conf
  META-INF/bundle.json     # name, version, platform, runtime, git commit
  META-INF/sbom.cdx.json   # CycloneDX SBOM, runtime included
```

This follows the `application` plugin's own conventions: one top-level directory
`<base>-<version>`, `bin/` launchers, every jar in `lib/`, and the archive written to
`build/distributions` as `<base>-<version>-<classifier>.<ext>`. The launcher names the
application's own jar first on the classpath and then the `lib/*` wildcard, so the application's
classes still win over a duplicate in a dependency.

The launcher takes `version`, `run` and `healthcheck`. Configuration is read from `conf/` if
present, falling back to `conf.defaults/`, so an upgrade never overwrites a deployed config file.

---

## Choosing a JDK: distribution, release and exact version

Three independent choices, each with a sensible default:

```kotlin
bundle {
    javaVersion = 21          // feature release; 11–25. Default: the catalogue default (21)
    jdkVersion = "21.0.13+9"  // exact build. Default: the catalogue pin for that release
}
```

```properties
# gradle.properties — which vendor, and an emergency patch ahead of the next plugin release
platform.runtime.jdk.distribution=temurin
platform.runtime.jdk.21=21.0.14+7
```

| Distribution | `jdkVersion` form | Source |
|---|---|---|
| `temurin` (default) | `21.0.13+9` — build number required | Adoptium GitHub release assets |
| `oracle` | `25.0.3` — no build number | `download.oracle.com/java` archive |

The **same** distribution and exact version is used for the compile toolchain and for the runtime
linked into the bundle, so they can never drift apart.

> **Licensing.** Whether you may redistribute a bundle with an Oracle runtime inside it is decided
> by Oracle's licence, not by this plugin. Recent releases under the NFTC permit it; OTN-licensed
> releases do not — which is why the Oracle 17 and 21 lines stop receiving free archive builds
> while 25 continues. Check the licence of the exact version you pin. Temurin is Apache-2.0 and has
> no such restriction.

### Approved pins

Shipped inside the plugin jar, in `runtimes.properties`. Releasing a new plugin version is how a
runtime bump reaches every repository at once:

```properties
jdk.default=21
jdk.temurin.11=11.0.32.1+1    jdk.oracle.17=17.0.12
jdk.temurin.17=17.0.21+9      jdk.oracle.21=21.0.12
jdk.temurin.21=21.0.13+9      jdk.oracle.25=25.0.3
jdk.temurin.25=25.0.5+8
node.default=24.18.1
```

Asking for a release with no pin fails with the property to set, rather than silently resolving
something else.

### Another vendor, or a mirror

Override the base URL and the Ivy artifact pattern. Between them they address any flat HTTP tree,
so a mirror, an internal build, or a vendor this plugin has never heard of needs no plugin change:

```properties
platform.public.jdkUrl=https://mirror.example.com/jdks
platform.public.jdkPattern=[organisation]/[revision]/[classifier]/jdk.[ext]
```

Available placeholders are `[organisation]`, `[module]`, `[revision]`, `[artifact]`, `[classifier]`
and `[ext]`. What each one holds depends on the distribution — Oracle puts the platform in
`[classifier]`, Temurin encodes it in `[artifact]` and leaves `[classifier]` empty — so keep the
distribution closest to your layout and override from there. `platform.public.nodeUrl` and
`platform.public.nodePattern` do the same for Node.

> Runtime archives are resolved as ordinary Gradle dependencies through an Ivy-layout repository
> rather than fetched by a download plugin. That is deliberate: it gets Gradle's artefact cache,
> credentials, `--offline`, and `gradle/verification-metadata.xml` checksum verification for free.
> A download plugin would re-implement all four, badly.

## Target platform and CPU architecture

Both the OS and the architecture are part of the target, and part of the published artefact's Maven
classifier:

```
linux-x64   linux-aarch64   windows-x64   mac-x64   mac-aarch64
```

```bash
./gradlew build -Pbundle.targetPlatform=linux-aarch64
```

A target that differs from the build host is a **cross build**. `jlink` runs from the host JDK
against the target JDK's `jmods`, which is how a Windows laptop with no WSL2 or container runtime
still produces a `linux-aarch64` runtime. A cross-built bundle cannot be started here, so its smoke
test is skipped and `publish` refuses it unless you pass `-Pbundle.allowUntestedPublish=true`.

Adding an architecture Temurin ships but this list omits (`ppc64le`, `s390x`) means adding one
constant to `Arch` and one entry to `Platform.SUPPORTED`. Treat the existing ids as frozen: they are
published classifiers.

---

## Machine configuration: public internet or Artifactory

Set once per machine or CI image in `~/.gradle/gradle.properties` — never per project, so the same
project builds unchanged either way:

```properties
platform.repositories=artifactory
platform.artifactory.url=https://artifactory.example.com/artifactory
artifactoryUsername=build-user
artifactoryPassword=<identity or reference token>
# platform.tls.caFile=/etc/pki/corporate-ca.pem   # for npm; Gradle uses the JVM trust store
```

Projects declare no repositories of their own. On CI, pass credentials as
`ORG_GRADLE_PROJECT_artifactoryUsername` / `ORG_GRADLE_PROJECT_artifactoryPassword`.

### Repositories to create

| Repository | Type | Notes |
|---|---|---|
| `maven-virtual` | Maven virtual | Remote to Maven Central plus local repos |
| `gradle-plugins-virtual` | Maven virtual | Remote to `https://plugins.gradle.org/m2/` plus `gradle-plugins-local` |
| `npm-virtual` | npm virtual | Remote to registry.npmjs.org |
| `runtimes` | Generic local | Curated runtime archives |
| `libs-release-local`, `libs-snapshot-local` | Maven local | Where bundles are published |

Note that `plugins.gradle.org` serves plugin *artefacts* from `plugins-artifacts.gradle.org`; a
remote proxy must be able to reach both. Most build plugins are also on Maven Central, so a
Central mirror covers the majority.

Default curated layout — one generic repository, files renamed on upload, vendor-independent:

```
runtimes/temurin/jdk/21.0.13_9/jdk-21.0.13_9-linux-x64.tar.gz
runtimes/oracle/jdk/25.0.3/jdk-25.0.3-windows-x64.zip
runtimes/nodejs/node/24.18.1/node-24.18.1-linux-x64.tar.gz
```

[`deploy/curate-runtimes.sh`](deploy/curate-runtimes.sh) downloads an approved release, verifies it
against the upstream SHA-256, renames it and uploads it with its checksum. Paths use `_` rather than
`+` because some HTTP clients decode `+` as a space.

If an existing Artifactory tree cannot be reshaped — a remote proxy mirroring a vendor's own layout
verbatim, say — point the patterns at it instead:

```properties
platform.artifactory.runtimes.jdkPattern=[organisation]/[module]/[revision]/[artifact].[ext]
platform.artifactory.runtimes.nodePattern=[organisation]/[module]/[revision]/[artifact].[ext]
```

### All properties

| Property | Default | Meaning |
|---|---|---|
| `platform.repositories` | `public` | `public` or `artifactory` |
| `platform.artifactory.url` | — | Base URL, required in Artifactory mode |
| `platform.artifactory.repo.maven` | `maven-virtual` | Maven libraries |
| `platform.artifactory.repo.gradlePlugins` | `gradle-plugins-virtual` | Gradle plugins |
| `platform.artifactory.repo.npm` | `api/npm/npm-virtual` | npm registry |
| `platform.artifactory.repo.runtimes` | `runtimes` | Curated JDK and Node archives |
| `platform.artifactory.repo.releases` / `.snapshots` | `libs-release-local` / `libs-snapshot-local` | Where bundles are published |
| `platform.artifactory.runtimes.jdkPattern` / `.nodePattern` | curated layout | Ivy pattern for the curated tree |
| `platform.artifactory.allowInsecureProtocol` | `false` | Allow `http://` (test instances only) |
| `artifactoryUsername` / `artifactoryPassword` | — | Credentials, used by every request |
| `platform.tls.caFile` | — | PEM bundle npm should trust (corporate TLS) |
| `platform.runtime.jdk.distribution` | `temurin` | `temurin` or `oracle` |
| `platform.public.jdkUrl` / `.jdkPattern` | distribution default | JDK source in public mode |
| `platform.public.nodeUrl` / `.nodePattern` | nodejs.org/dist | Node source in public mode |
| `platform.public.npmRegistry` | registry.npmjs.org | npm registry in public mode |
| `platform.runtime.jdk.<feature>` | catalogue | Override an exact JDK pin, e.g. `platform.runtime.jdk.21=21.0.14+7` |
| `platform.runtime.node` | catalogue | Override the Node pin |
| `bundle.targetPlatform` | build host | See *Target platform* above |
| `bundle.allowUntestedPublish` | `false` | Permit publishing a cross-built (never-started) bundle |
| `bundle.gitCommit` | `git rev-parse HEAD` | Commit recorded in `bundle.json` |

---

## Extension reference

```kotlin
bundle {
    // shared by both plugins
    appName = "orders-service"                   // default: project name
    targetPlatform = platform("linux-x64")       // default: -Pbundle.targetPlatform, else host
    configDefaults = file("src/bundle/conf")     // shipped as conf.defaults/
    smokeTestEnabled = true
    smokeTestTimeout = Duration.ofSeconds(60)
    allowUntestedPublish = false

    // java-bundle only
    javaVersion = 21                             // feature release, 11–25
    jdkVersion = "21.0.13+9"                     // exact build; default: catalogue pin
    jlinkModules = listOf("java.base")
    jlinkOptions = listOf("--strip-debug", "--no-man-pages", "--no-header-files", "--compress=zip-6")
    jvmArgs = listOf("-XX:MaxRAMPercentage=75")  // baked into the launcher, before $APP_JAVA_OPTS

    // node-bundle only
    nodeVersion = "24.18.1"                      // default: catalogue pin
    entryPoint = "main.js"                       // script under app/ that `run` starts
    appFiles = files("dist")                     // default: src/ minus src/bundle/, or dist/
    buildScript = "build"                        // npm script run before packaging
    testScript = "test"                          // npm script run by `check`
    ignoreInstallScripts = true                  // --ignore-scripts on the production install
    portableInstallScripts = listOf("esbuild")   // vetted, do not block cross builds
    nodeArgs = listOf("--enable-source-maps")
}
```

`jlinkOptions` defaults to stripping debug info, man pages and headers, plus `--compress=zip-6` on
Java 21 and newer (`--compress=2` before that, where the named levels do not exist).

## Tasks

| Task | Group | Notes |
|---|---|---|
| `bundleTar` | bundle | The archive; `assemble` depends on it |
| `bundleSmokeTest` | verification | Extracts, runs `version` + `run` + `healthcheck`; `check` depends on it; skipped for cross builds |
| `runBundle` | application | Runs the assembled bundle; `-Pargs="…"` |
| `bundleJlink` | bundle | Java: the trimmed runtime |
| `bundleDockerContext` | bundle | Container build context from the archive |
| `npmInstall` / `npmTest` / `npmBuild` | bundle / verification | Node: `npm ci`, the `test` script, `bundle.buildScript` |
| `npmInstallProduction` | bundle | Node: `npm ci --omit=dev` for the target platform |
| `bundleVerifyNodeModules` | verification | Node: install-script check |
| `bundleVerifyPublishable` | verification | Guards `publish*` |

The Java plugin disables the `application` plugin's `distTar` / `distZip` so there is one way to
ship the app; `installDist` still works.

## Publishing a bundle

`publish` attaches the archive to the project's Maven coordinates with the platform as the
classifier, alongside its SBOM and Gradle Module Metadata:

```
com/example/orders-service/1.4.2/orders-service-1.4.2-linux-x64.tar.gz
com/example/orders-service/1.4.2/orders-service-1.4.2-linux-x64-cyclonedx.json
com/example/orders-service/1.4.2/orders-service-1.4.2.module
com/example/orders-service/1.4.2/orders-service-1.4.2.pom
```

The publication is a real Gradle software component, so the module metadata records the target
machine using Gradle's standard attributes:

```json
"attributes": {
  "org.gradle.category": "platform-bundle",
  "org.gradle.dependency.bundling": "embedded",
  "org.gradle.native.operatingSystem": "linux",
  "org.gradle.native.architecture": "x86-64"
}
```

A consuming Gradle build can then resolve the bundle for its own machine rather than spelling out a
classifier. Maven-only consumers — `deploy/install.sh`, an Artifactory REST call — are unaffected,
because the classifier is still on the artifact.

> One build produces one platform, so each platform build publishes its own module metadata. If you
> publish several platforms to the **same** coordinates from separate builds, the last `.module`
> wins and describes only that platform. Either give each platform its own `artifactId`, or turn the
> metadata off:
> ```kotlin
> tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }
> ```

Set `-PsigningKey=…` (and `-PsigningPassword=…`) to sign publications. Archives are reproducible:
rebuilding the same commit yields an identical checksum.

---

## Working on these plugins

The plugins live in [`build-src/`](build-src), included by the root build as a plugin build. The
root build then **applies the settings plugin to itself**, so a change that breaks consumers breaks
this build too.

```bash
./gradlew check                 # ktlint, detekt, unit tests, TestKit functional tests
./gradlew -p samples/java-service build
./gradlew -p samples/node-service build

./gradlew publish        -PreleaseVersion=1.2.0   # corporate Gradle plugin repository
./gradlew publishPlugins -PreleaseVersion=1.2.0   # Gradle Plugin Portal
```

`./gradlew dependencyUpdates` reports available upgrades within the current major version.
`./gradlew spotlessApply` fixes formatting.

The plugin's own publication carries everything Maven Central requires: sources and javadoc jars,
and a POM with name, description, URL, licence, developer and SCM. Signing is opt-in by key, so
`publishToMavenLocal` needs no signatory while the release pipeline passes `-PsigningKey`.

Unit tests in `build-src/src/test` assert the exact download URL for every distribution and
platform — the one thing a functional test cannot check without reaching each vendor's real site.
