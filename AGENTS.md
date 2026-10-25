# Working on this repository

Notes for anyone (human or agent) changing the plugins. The [README](README.md) is for people
*using* them and must stay free of anything below.

## Layout

```
build-src/                  the plugin build, included by the root build
  build-logic/              convention plugins: embedded-runtime.kotlin, embedded-runtime.gradle-plugin
  core/                     io.github.fiftieshousewife:app-core      package …appcore
  java-app/                 io.github.fiftieshousewife:java-app      package …javaapp
  node-app/                 io.github.fiftieshousewife:node-app      package …nodeapp
  config/detekt/detekt.yml  deviations from detekt's defaults only
deploy/                     install.sh (servers), curate-runtimes.sh (Artifactory)
gradle/init.d/              init script for machines behind Artifactory
gradle/libs.versions.toml   the one version catalogue, shared by every build here
samples/                    a Java and a Node service, built in CI end to end
```

The root build applies **both** settings plugins to itself, so a change that breaks consumers, or
breaks applying both together, breaks this build.

## Build and test

```bash
./gradlew check                                  # every module: ktlint, detekt, unit + functional tests
./gradlew -p build-src :java-app:functionalTest  # one module's TestKit tests
./gradlew -p samples/java-service build          # end to end, against the real internet
./gradlew -p samples/java-service build -PjavaApp.jdk.distribution=zulu
./gradlew -p build-src spotlessApply             # fix formatting
./gradlew -p build-src dependencyUpdates         # upgrades within the current major
```

Detekt and ktlint are blocking. Unit tests (`src/test`) cover pure logic, above all every vendor's
download URL. Functional tests (`src/functionalTest`) build real throwaway projects through TestKit
against a fake download site on disk (`TestProject` in `core/src/testFixtures`, plus `JdkFixture`
and `NodeFixture`), so they need no network except the Kotlin test, which resolves the Kotlin
plugin from the portal.

Known test caveats:

- Fixtures use the system `tar`, so functional tests skip on Windows; CI's Windows leg still builds
  both samples natively.
- The Node app test skips where npm does not sit next to `node` (Homebrew); CI runs it.
- `KotlinAppTest` turns the configuration cache off: under TestKit's injected plugin classloader the
  Kotlin plugin's build services fail to reload from the cache. Real builds are unaffected.
- `JavaAppPluginTest` drives `deploy/install.sh` end to end (install, upgrade, status, rollback),
  so a change to the installer is tested there.

## How it fits together

**`app-core` is shared machinery, not a public API.** It is published only because both plugins
depend on it, and all three are always released together at one version (`PluginVersion` reads it
from a generated resource). `AppWiring` builds the shared pipeline:

```
appLaunchers ─┐
appManifest  ─┼─► appAssemble ─► appTar ─► appSmokeTest ─► publish
appSbom      ─┘                     └────► appDockerContext
```

Each plugin contributes an `Ecosystem` (its names: `java`/`javaApp`/settings plugin id) and an
`EcosystemSpec` (runtime name and version, launcher exec lines, archive contents, SBOM inputs).
Everything Java- or Node-specific stays in its own module.

**Settings plugins exist because three things can only happen in settings:** repository
configuration for the whole build, registering the JDK toolchain resolver, and pinning the project
plugins' versions so build scripts give none. `AppSettings` holds the shared part and is idempotent,
because a build with Java and Node apps applies both. Each project plugin fails fast, naming the
line to add, when its settings plugin is missing (`markApplied` / `requireApplied`).

**Runtimes are ordinary Gradle dependencies** from an Ivy repository over a plain file tree
(`metadataSources { artifact() }`), one `exclusiveContent` repository per runtime group. That buys
Gradle's cache, credentials, `--offline` and `verification-metadata.xml` without a download plugin.
`RuntimeRepository.downloadUri` reproduces Ivy's URL so the toolchain resolver can hand the same URL
to Gradle's toolchain provisioning.

**One pin, two consumers.** `JdkCatalog.pinnedJdk` (a `javaApp.jdk.<feature>` override, else the
catalogue in `jdks.properties`) is read by both `JdkToolchainResolver` (compile JDK) and
`JavaAppPlugin` (embedded runtime), from the same `JdkSources`, so they can never differ.

**`JdkDistribution` owns everything vendor-specific:** the Gradle vendor spec, the default URL and
Ivy pattern, how a version and target become file names, and the regex a pin must match. Versions
are opaque strings that start with the feature release; each vendor validates its own form.

**Cross builds** run `jlink` from the host's JDK against the target JDK's `jmods/`. The archive
can't be started on the host, so `appSmokeTest` is skipped and `appVerifyPublishable` blocks
`publish` unless explicitly allowed.

**Archives** are always `.tar.gz` (Windows 10+ has `tar.exe`) with one top-level directory,
reproducible ordering and timestamps, and exec bits set explicitly (`AppWiring.EXECUTABLES`), since
a Windows checkout or Windows jlink run carries none. The publication is a real software component
whose Gradle Module Metadata carries the standard OS and architecture attributes.

**Configuration cache:** lambdas stored in tasks (`onlyIf`, providers resolved at execution) must
not capture `AppWiring` or anything else holding the `Project`; copy what they need into locals.

**Artifactory credentials** come from Gradle's `artifactoryUsername`/`artifactoryPassword` by
repository *name*, which must be `artifactory` and is unique per container. Other repositories in
the same container (the runtime Ivy repositories) set credentials explicitly instead.

## Common changes

**Bump runtime pins.** Edit `java-app/src/main/resources/…/jdks.properties` or
`node-app/…/node.properties`. A pin must resolve on **every** supported target: vendors upload a
release one platform at a time, so check all five URLs exist before pinning (the unit tests prove the
URL shape, not that the file exists). The `every catalogue pin…` test checks each pin's form.

**Add a JDK vendor.** Add a `JdkDistribution` entry (vendor spec, base URL, pattern, version regex,
example, `artifact()`), pins in `jdks.properties`, URL assertions for Linux, macOS and Windows in
`JdkSourcesTest` and a rejected example in its version test, and a row in the README table. Then
build `samples/java-service` with `-PjavaApp.jdk.distribution=<id>`.

**Add a target.** One constant in `Arch` or `Os` and one entry in `Target.SUPPORTED`. Target ids
are published Maven classifiers: never change an existing one.

**Release.** `./gradlew publish -PreleaseVersion=x.y.z -PsigningKey=… -PsigningPassword=…` puts all
three modules on Maven Central (or the corporate repository); then `./gradlew publishPlugins
-PreleaseVersion=x.y.z` publishes the two plugins to the Gradle Plugin Portal. `app-core` must
already be on Central, because the portal resolves plugin dependencies from there.

## Conventions

- Names say what things are: "app" and "archive", "target" for OS and CPU. Avoid "bundle" and
  "platform", which were too general.
- Gradle properties are prefixed by their plugin (`javaApp.`, `nodeApp.`) or by `artifactory.` for
  machine-wide Artifactory settings, which both plugins read.
- KDoc explains *why*; the code already says what.
- The README describes behaviour a user sees; design notes live here.
