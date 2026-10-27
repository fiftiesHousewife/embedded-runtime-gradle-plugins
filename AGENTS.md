# Working on this repository

Notes for anyone (human or agent) changing the plugins. What has been asked for and not yet done is in [BACKLOG.md](BACKLOG.md) (keep it current).
The [README](README.md) and [`docs/`](docs)
are for people *using* them and must stay free of anything below. Keep the README a short overview
that links into `docs/`.

## Layout

```
build-src/                  the plugin build, included by the root build
  build-logic/              convention plugins: embedded-runtime.kotlin, embedded-runtime.gradle-plugin
  core/                     io.github.fiftieshousewife:app-core      package …appcore (+ app-smoke-test plugin)
  java-app/                 io.github.fiftieshousewife:java-app      package …javaapp
  node-app/                 io.github.fiftieshousewife:node-app      package …nodeapp
  detekt-rules/             the repository's own detekt rules (FileLength, CommentDensity); not published
  config/detekt/detekt.yml  deviations from detekt's defaults only
scripts/                    curate-runtimes.sh (mirrors approved runtimes into the binary repository)
renovate/                   Renovate presets for users: temurin and nodejs pin overrides; ubi, a reference example
docs/                       user documentation, linked from the README; article.md is the article's home
.github/scripts/            verify-rpm.sh: CI installs the sample's RPM in a Rocky Linux container
.claude-plugin/             the Claude Code marketplace manifest (must stay at the root)
ai/                         the AI assistant files the plugins ship: skills, the shared post-edit hook,
                            Copilot and Devin hook configs, the Claude plugin (see ai/README.md); ./gradlew
                            syncAiSkills updates the Claude plugin's copies; keep the skills in step with the
                            tasks and properties they name. Copilot's instructions for this repo: .github/
gradle/init.d/              init script for machines behind a binary repository
gradle/libs.versions.toml   the one version catalogue, shared by every build here
samples/                    a Java and a Node service, built in CI end to end
tools/renovate-validator/   Renovate's config validator (package.json and lockfile) for validateRenovatePresets
```

The root build applies **both** settings plugins to itself, so a change that breaks consumers, or
breaks applying both together, breaks this build.

## Build and test

```bash
./gradlew check                                  # every module: ktlint, detekt (with type resolution), Forbidden APIs, JaCoCo, all tests
./gradlew -p build-src :java-app:functionalTest  # one module's TestKit tests
./gradlew -p samples/java-service build          # end to end, against the real internet
./gradlew -p samples/java-service build -PjavaApp.jdk.distribution=zulu
./gradlew -p build-src spotlessApply             # fix formatting
./gradlew -p build-src dependencyUpdates         # upgrades within the current major
```

ktlint, detekt and Forbidden APIs are blocking. Unit tests (`src/test`) cover every `internal`
decision explicitly: vendor download URLs (`jdk-download-urls.csv`), version forms, security policy
merging, certificates, the module search, launcher lines, SBOM contents and npm arguments. Functional tests (`src/functionalTest`) build real throwaway projects through TestKit
against a fake download site on disk (`TestProject` in `core/src/testFixtures`, plus `JdkFixture`
and `NodeFixture`), so they need no network except the Kotlin test, which resolves the Kotlin
plugin from the portal.

Where things are tested:

- `JavaAppPluginTest`: the archive's contents and reproducibility, re-extracting a JDK with
  read-only files, launchers from Gradle's `startScripts` (and a build's own generator winning).
- `JavaAppTargetsTest`: several targets in one build and one publication, the container context,
  JDKs from a directory and from a shared local path, and the `app-smoke-test` consumer plugin
  (`ConsumerPluginsTest`, including its runtime policy check).
- `JavaAppModulesTest`: the jdeps default and `jlinkFindModules` fixing a missing module.
- `JavaAppSecurityTest`: certificates, policy and ciphers seen by the running JVM (a probe program run
  on the built runtime), the customisation record and overlay, and the forbidden-module guard.
- `JavaAppPackagingTest`: the zip format end to end, the RPM's header and file list (read back by
  `RpmFile` in the test fixtures), and the RPM upload to a `RecordingRepository`.
- `KotlinAppTest`, `NodeAppPluginTest`, the settings plugin tests, and the URL unit tests.

Known test caveats:

- Fixtures use the system `tar`, so functional tests skip on Windows; CI's Windows leg still builds
  both samples natively.
- `NodeFixture` repackages the `node` on PATH with its npm, found next to node (nodejs.org layout)
  or by following the `npm` on PATH (Homebrew).
- `KotlinAppTest` turns the configuration cache off: under TestKit's injected plugin classloader the
  Kotlin plugin's build services fail to reload from the cache. Real builds are unaffected.

## How it fits together

**`app-core` is shared machinery, not a public API,** apart from the small `app-smoke-test` plugin
it also carries for consuming builds. It is published because both plugins depend on it, and all
three are always released together at one version (`PluginVersion` reads it from a generated
resource). `AppWiring` builds the shared pipeline; `TargetPipeline` the part built once per target:

```
startScripts (Java) / appLaunchers<T> (Node) ─┐
appManifest<T>, appSbom<T>                     ─┼─► distribution <target> ─┬─► <target>DistTar/Zip ──► publish
runtime/ (customiseRuntime<T> or Node)          ─┘    (Gradle's plugin)      └─► install<Target>Dist (build/app/<target>)
                                                         appVerifyPolicy<T> ─┘   checked before any archive
                     install<Target>Dist ──► appRpm<T> (Linux, rpm enabled) ──► appPublishRpm
                     this machine's      ──► appSmokeTest, runApp (HostTasks), jlinkFindModules
                     a Linux one         ──► appDockerContext (DockerContext)
```

Each plugin contributes an `Ecosystem` (its names: `java`/`javaApp`/settings plugin id) and, per
target, an `EcosystemSpec` (runtime name and version, where the launchers come from, archive
contents, SBOM inputs). Everything Java- or Node-specific stays in its own module.

**Targets are known only after the build script runs,** so per-target tasks are registered in
`afterEvaluate`, as are the Java plugin's JDK tasks (the Java release is final then too). Tasks a
build script might name (`appArchive`, `appRpm`, `appSmokeTest`, `runApp`, `appDockerContext`, the publication)
are registered up front and wired to their target afterwards. Values feeding conventions set at
apply time but known later (the jdeps modules) go through a property set in `afterEvaluate`.

**One smoke test, three users.** `SmokeRun` starts an extracted app through its launcher and waits
for its health check. `AppSmokeTest` reads the name, version and target from each archive's
`META-INF/app.json`, so the producing build and the consumer plugin use the same task, and
`JlinkFindModules` reruns `SmokeRun` against freshly linked runtimes, reading the failure output for
missing JDK classes (mapped to modules with `jimage list`).

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
catalogue in `jdks.properties`, generated from `gradle/libs.versions.toml`) is read by both `JdkToolchainResolver` (compile JDK) and
`JavaAppPlugin` (embedded runtime), from the same `JdkSources`, so they can never differ.
`JdkSources` holds a `JdkLocation`: the vendor's site (`Public`) or a renamed tree (`Curated`, for
a binary repository or a local directory). A JDK configured by path for one release and target bypasses all
of that (`Jdks` in `JavaAppPlugin` uses a directory as it is and extracts an archive); the resolver
can serve such an archive but must leave a directory to Gradle's installation detection.
`JdkTools.link` refuses a tool JDK and target JDK of different releases.

**`JdkDistribution` owns everything vendor-specific:** the Gradle vendor spec, the default URL and
Ivy pattern, how a version and target become file names, and the regex a pin must match. Versions
are opaque strings that start with the feature release; each vendor validates its own form.

**Cross builds** run `jlink` from the host's JDK against the target JDK's `jmods/`. Those archives
can't be started on the host, so only the host's archive is smoke-tested and `appVerifyPublishable`
blocks `publish` while any archive is untested, unless explicitly allowed.

**Archives** are `.tar.gz` by default (Windows 10+ has `tar.exe`), or `.tar`/`.zip`
(`ArchiveFormat`; the task type is chosen when targets are registered), with one top-level
directory, reproducible ordering and timestamps, and exec bits set explicitly
(`AppCopySpecs.executables`), since a Windows checkout or Windows jlink run carries none. Anything
that reads an archive back goes through `archiveTree`, which handles every format.

**RPMs** (`RpmPackages`, `GenerateRpm`, `RpmContents`) are written by Redline in plain Java from the
assembled directory, laid out by `RpmLayout` (names, version mapping, FHS paths) with the unit and
scriptlets as resource templates under `appcore/rpm/`. Redline is from 2021: the build drops its Ant
tasks and replaces its compression and BouncyCastle dependencies with current releases, and it
stamps payload files with the current time, so RPMs are not reproducible. Nebula's ospackage
plugin was not used: it pulls in a Docker plugin, and 13.x (the first with configuration-cache
support) was days old. `docs/packaging-standards.md` records the conventions; change it with the
code. The publication is a real software component
whose Gradle Module Metadata carries the standard OS and architecture attributes.

**Configuration cache:** lambdas stored in tasks (`onlyIf`, providers resolved at execution) must
not capture `AppWiring` or anything else holding the `Project`; copy what they need into locals.

**Runtime customisation** (`CustomiseRuntime`, one per target, between `jlink` and the archive)
copies the linked runtime, refuses forbidden modules (`ForbiddenModules`, from the runtime's `release`
file), installs certificates with the JDK's own `KeyStore` (`TrustedCertificates`), appends the
policy's changes to `java.security` (`SecurityPolicy`: later entries win, so the JDK's file stays
intact; disabled-algorithm lists are extended, never replaced), copies the overlay, and writes
`conf/security/java-app-customisations.txt`. It is an ordinary task so builds can extend it.

**Shared module sets** (`ModuleSets`): jlink links the smallest of a few fixed sets holding the
app's modules rather than exactly those, so apps on one set share a byte-identical runtime layer.
The sets are data, in java-app's `module-sets.properties` (`set.<name>`, and `lastFeature.<module>`
for a module later releases no longer have); keep each to modules every supported release has.
`jlinkModules` stays the app's own need, which `jlinkFindModules` and the fallback use.

**SBOMs and image labels come from standard libraries and specs:** `CycloneDx` builds the SBOM with
the CycloneDX reference model and package-url's purl builder (no timestamp, so rebuilds are
byte-identical); `DockerfileTemplate` fills in the OCI `org.opencontainers.image.*` annotations.

**Settings in code.** Every setting the settings plugins read can be set in `settings.gradle.kts`'s
`embeddedRuntime {}` block (`EmbeddedRuntimeSettings`), whose setters are named for the Gradle
properties they stand for (unit tests check the names). Once the settings script has run, the
values are recorded in `SettingsInCode`, which `ProviderFactory.setting()` consults before the
property, so every reader, the toolchain resolver included, sees them; the settings plugins
therefore configure repositories in `settingsEvaluated`, not when applied. Project-level settings
are extension properties whose conventions are the properties (the runtime policy is the
exception: its machine-wide property wins over the build).

**Binary repository credentials** come one of two ways. With `binaryRepo.repository`, the settings
plugins wait for the settings script (`AppSettings.withBinaryRepository`), find that repository,
and record its server and login (`BinaryRepository.borrow`), read with the public
`getCredentials(org.gradle.api.artifacts.repositories.PasswordCredentials)`. That call gives a
repository without credentials empty ones, which breaks it, so borrowing one without a user name
stops the build: there is no login to lend, and `binaryRepo.url` serves anonymous servers. The record is kept by name in `BinaryRepository` because the JDK toolchain resolver
can't see the settings; that is per daemon, not per build, so two builds of one composite that
borrow the same name with different servers or logins would see whichever was recorded last. With `binaryRepo.url`, Gradle reads `binaryRepoUsername`/`binaryRepoPassword`
by repository *name* (`binaryRepo`, unique per container). Every other consumer (runtime Ivy
repositories, npm, publishing, RPM upload, certificate downloads) takes `BinaryRepository.login`.

## Common changes

**Every version lives in `gradle/libs.versions.toml`:** libraries and build plugins, the build's
JaCoCo and bytecode level, and what the plugins embed. The JDK pins (`jdk-<distribution>-<feature>`),
the Node.js pin (`node`) and the Dockerfile base image (`ubi-minimal-tag` and `ubi-minimal-digest`) are written into
`jdks.properties`, `node.properties` and `version.properties` in the jars by `jdkPins`, `nodePin` and
`versionResource`. Outside it by necessity: the Gradle version (`gradle-wrapper.properties`), Kotlin
(embedded in Gradle, used by `kotlin-dsl`), and CI's setup versions (`.github/workflows/ci.yml`). Renovate's own version is in
`tools/renovate-validator/package.json` and its lockfile, which Renovate's npm support updates (a
catalogue version names no Maven module, so Renovate would not).

**Bump runtime pins.** Edit the `jdk-…` or `node` version in `gradle/libs.versions.toml`. A pin must resolve on **every** supported target: vendors upload a
release one platform at a time, so check all five URLs exist before pinning (the unit tests prove the
URL shape, not that the file exists). The `every catalogue pin…` test checks each pin's form.

**Bump the Dockerfile base image.** `ubi-minimal-tag` and `ubi-minimal-digest` in the catalogue
move together; take the digest from the registry as the comment there says.

**Renovate presets** in `renovate/` are checked by `validateRenovatePresets` (part of the root
`check`): Renovate's own validator, run with npx by the Node Gradle plugin on the Node.js the
catalogue pins (`node`). Node.js comes from nodejs.org through a repository declared in
`settings.gradle.kts`, since the settings plugins prefer settings repositories over the Node
plugin's own; `BinaryRepository` counts nodejs.org as public, or it would take it for the
organisation's binary repository. `ubi.json` is a reference example for Renovate maintainers
(`docs/renovate.md`).

**Add a JDK vendor.** Add a `JdkDistribution` entry (vendor spec, `JdkSite`, `VersionForm`, and an
`artifact()` built from an `ArchiveName` and the vendor's `Spelling`), `jdk-<id>-<feature>` pins in the catalogue,
Linux, macOS and Windows rows in `java-app/src/test/resources/jdk-download-urls.csv`, a rejected
example in `JdkVersionFormsTest`, and a row in the README table. Then build `samples/java-service`
with `-PjavaApp.jdk.distribution=<id>`.

**Add a target.** One constant in `Arch` or `Os` and one entry in `Target.SUPPORTED`. Target ids
are published Maven classifiers: never change an existing one.

**Release.** As the Clean Code plugin does: `./gradlew publishToMavenLocal -PreleaseVersion=x.y.z`
to try it, `./gradlew verifyMavenCentralBundle -PreleaseVersion=x.y.z` to stage the release into
`build-src/build/central-staging` and check it (every POM named "Embedded Runtime: …" with the
metadata Central requires, every file with checksums and, when a key is set, a signature; CI runs
it unsigned on every push), then `./gradlew publishToMavenCentral -PreleaseVersion=x.y.z`, which
uploads that bundle through the Central Portal's Publisher API as one deployment named
`embedded-runtime-gradle-plugins-x.y.z` and waits until Central has published it. Vanniktech's plugin
still builds the POMs, sources and Dokka javadoc jars and signs them, but not the upload: it names a
multi-module deployment after a group and a timestamp, with no way to change it. Credentials come from `~/.gradle/gradle.properties` or
`ORG_GRADLE_PROJECT_*`: `mavenCentralUsername`/`mavenCentralPassword` (a Central Portal token for
the `io.github.fiftieshousewife` namespace) and a signing key (`signing.keyId`/`signing.password`/
`signing.secretKeyRingFile`, or `signingInMemoryKey`/`signingInMemoryKeyPassword`); every artifact
is signed when one is set. Users then list `mavenCentral()` in `pluginManagement`. Plain `publish`
publishes to the staging directory and, when a binary repository is configured, the corporate repository. `./gradlew publishPlugins` would add the Gradle
Plugin Portal, with its own key and a manual review of each new plugin.

## Conventions

The coding standards follow the Clean Code plugin ([fiftiesHousewife/Clean-Claude](https://github.com/fiftiesHousewife/Clean-Claude)),
enforced with standard tools rather than the plugin itself; the thresholds live in
`config/detekt/detekt.yml` and `.editorconfig`:

| Standard | Enforced by |
|---|---|
| Lines of at most 120 characters | ktlint (Spotless) and detekt |
| Functions of at most 30 lines, at most 4 parameters (data classes exempt), classes of at most 150 lines | detekt |
| Files of at most 200 non-blank lines; at most 15% of a file's lines comments (the DSL types users configure exempt: their KDoc is the reference) | detekt, with `detekt-rules` |
| Cyclomatic and cognitive complexity at most 10, nesting at most 3 | detekt |
| No magic strings, no `*Helper`/`*Util`/`*Manager`/`*Processor`, no `handle`/`process`/`do`/`perform` names | detekt |
| No swallowed exceptions, no `println`/`printStackTrace`, no legacy `java.io` streams | detekt |
| No default-charset or default-locale JDK calls, nothing deprecated or internal | Forbidden APIs |
| Coverage reported | JaCoCo |

And by hand, since no tool checks them:

- Small functions, each with one job; decisions extracted into `internal` functions or objects with
  their own unit tests. Collection operations rather than loops (a `tailrec` where an attempt must
  repeat).
- Tests: several checks go in one `assertAll` (`checks(...)` turns a list of lambdas into
  `Executable`s); no comments inside tests; fixture sources (Java, Kotlin, JavaScript, build scripts)
  live in `src/*/resources/fixtures`, build scripts with a `.template` suffix so ktlint leaves them
  alone.
- Libraries first: CycloneDX and package-url for SBOMs, the JDK's `KeyStore`, `CertificateFactory`,
  `HexFormat` and `java.nio.file`, Gradle's archive and copy tasks, Groovy's JSON. Hand-written code
  only where nothing standard exists (Ivy URL patterns for the toolchain resolver, the launchers).
  `java.io.File` stays where Gradle's own API hands one out.
- No `@Suppress`: a rule that does not fit is a design to change, or a threshold to discuss.

- Names say what things are: "app" and "archive", "target" for OS and CPU. Avoid "bundle" and
  "platform", which were too general.
- Gradle properties are prefixed by their plugin (`javaApp.`, `nodeApp.`) or by `artifactory.` for
  machine-wide binary repository settings, which both plugins read.
- KDoc explains *why*; the code already says what.
- The README describes behaviour a user sees; design notes live here.
