# Reference

## `javaApp {}` and `nodeApp {}`

```kotlin
javaApp {                                       // nodeApp {} has the same settings down to smokeTestEnabled, and image and images
    appName = "orders-service"                  // launcher, archive and RPM name; default: project name
    targets("linux-x64", "windows-x64")         // default: -PjavaApp.targets, else this machine
    rpm {                                       // see Packaging and deploying
        enabled = false                         // default: -PjavaApp.rpm
        user = "orders-service"                 // service account; default: appName
        systemd = true                          // install and manage a systemd unit
    }
    license = "Apache-2.0"                      // SPDX; default LicenseRef-Proprietary (image label, RPM)
    configDefaults = file("src/deploy/conf")    // shipped as conf.defaults/; default: node-app only
    sbom = file("build/reports/bom.json")       // your own CycloneDX SBOM instead of the built-in one
    securityPolicy = "/etc/acme/java-policy"    // a directory or zip, or "com.acme:java-runtime-policy:7"; -PjavaApp.securityPolicy wins over this
    smokeTestEnabled = true                     // start the archive during `check`

    images {                                    // one Dockerfile.<name> each; see On UBI micro in Testing the container image
        register("ubi-micro") {
            from(ubiMicro)                      // FROM; default ubiMinimal, or your mirror's image, by digest
            install("libstdc++")                // RPMs beyond what the ecosystem needs; locked in rpms.lock.yaml
            env("TZ", "Europe/London")          // after the plugin's own (APP_*, HOME, LANG=C.UTF-8), which it can override
            label("summary", "Order service")   // after the plugin's own OCI and Red Hat labels, which it can override
            expose(8080)                        // default 8080; the first port also sets APP_PORT
            user("1001")                        // the default, in group 0; "" for none
            // runtimeOnly()                    // the runtime alone: no app layers, port or health check
        }
    }
    image {                                     // RPMs for Dockerfiles you write yourself
        packages("zlib")                        // locked with every image's own
        repositories.put("ubi-9-baseos-rpms", "https://repo.example.com/ubi-9/[arch]/baseos/os/") // default: binaryRepo.repo.ubi's baseos and appstream
        lockFile = file("rpms.lock.yaml")       // the default, at the project's root; commit it
    }

    javaVersion = 25                            // 11 to 27
    additionalModules = listOf("jdk.localedata") // added to what jdeps finds
    moduleSets.put("batch", listOf("java.base", "java.sql")) // default: base, service, framework, server; empty: each app's own modules
    runtimeOverlay = file("src/deploy/runtime") // copied over the runtime as it is; this is the default
    security { … }                              // see Hardening the runtime
    runtimeDefaults = true                      // -XX:MaxRAMPercentage=75, exit and heap dump in $APP_LOG_DIR on OutOfMemoryError
    jvmArgs = listOf("-Xss512k")                // after runtimeDefaults, before $APP_JAVA_OPTS
}

nodeApp {
    entryPoint = "main.js"                      // under app/; started by `run`
    appFiles = files("dist")                    // default: src/ (minus src/deploy/), or dist/ with buildScript
    buildScript = "build"                       // npm script run before packaging
    testScript = "test"                         // npm script run by `check`, if package.json has it
    installScripts = listOf("esbuild")          // vetted packages whose install scripts run; default: none, npm --ignore-scripts
    nodeArgs = listOf("--enable-source-maps")   // before $APP_NODE_OPTS
    security {
        trustedCertificates.from("certs")       // PEM or DER CAs, added to Node's own via NODE_EXTRA_CA_CERTS
        tlsMinVersion = "1.3"                   // --tls-min-v1.3; "1.2" or "1.3"
        tlsCipherList = "TLS_AES_256_GCM_SHA384" // --tls-cipher-list
    }
}
```

Every archive is a zip for Windows and a tar.gz for Linux and macOS. The Dockerfile is the project's root `Dockerfile`. The
image label's source and the RPM's URL are the `origin` git remote. An RPM is named after `appName`, with release `1`, the
app's `license`, and the project's `description` as its summary (default: "<app> with its own runtime").

## `embeddedRuntime {}` in `settings.gradle.kts`

Everything the settings plugins read, set in code. Each setting stands for the Gradle property in its comment and wins over
it; whatever is left unset still comes from the properties, so settings in `~/.gradle/gradle.properties` keep working.

```kotlin
embeddedRuntime {
    binaryRepository {
        repository = "artifactory"              // binaryRepo.repository: borrow a repository the build declares
        url = "https://repo.example.com"        // binaryRepo.url
        mavenRepo = "maven-virtual"             // binaryRepo.repo.maven; likewise gradlePluginsRepo,
        runtimesRepo = "runtimes"               // runtimesRepo, releasesRepo, snapshotsRepo, npmRepo, ubiRepo
        allowInsecureProtocol = false           // binaryRepo.allowInsecureProtocol
    }
    jdk {
        distribution = "oracle"                 // javaApp.jdk.distribution
        pin(25, "25.0.3")                       // javaApp.jdk.25
        source = "yum:yum-local/rhel/9"         // javaApp.jdk.source
        pattern = "jdk-[feature]"               // javaApp.jdk.pattern
        reuseToolchain = true                   // javaApp.jdk.reuseToolchain
        path(25, "windows-x64", "C:/Program Files/Java/jdk-25") // javaApp.jdk.25.windows-x64.path
    }
    node {
        version = "24.22.1"                     // nodeApp.node.version
        source = "runtimes-local"               // nodeApp.node.source; and pattern
        npmRegistry = "https://npm.example.com" // nodeApp.npm.registry; and npmCaFile
    }
}
```

## `appSmokeTest {}`, in a build applying `app-smoke-test`

```kotlin
appSmokeTest {
    signerIdentity = "^https://github\\.com/acme/orders/\\.github/workflows/release\\.yml@refs/tags/v.*$"
    signerIssuer = "https://token.actions.githubusercontent.com" // the default
    sigstoreTrustedRoot = file("/etc/sigstore/trusted_root.json")
    securityPolicy = "/etc/acme/java-policy"    // -PappSmokeTest.securityPolicy wins over this
    healthTimeout = Duration.ofSeconds(90)
}
```

Each defaults to the `-PappSmokeTest.<name>` property of the same name.

## Tasks

`./gradlew build` runs everything a release needs: `assemble` builds the archives (and RPMs), and `check` runs your tests,
checks every archive against the runtime policy, and starts this machine's archives. `<Target>` stands for a target in task
names: `LinuxX64`, `LinuxAarch64`, `MacAarch64`, `MacX64`, `WindowsX64`. Settings are in `javaApp {}` or `nodeApp {}` unless
they start with `-P`, which marks a Gradle property.

### Building the archives

| Task | What it does | Settings |
|---|---|---|
| `appArchive` | Builds every target's app, libraries and runtime archives into `build/distributions/`; part of `assemble` | `targets(…)`, `appName`; `-PjavaApp.targets` |
| `<target>DistTar`, `<target>DistZip` | One target's app archive, `<app>-<version>-<target>.tar.gz` (`.zip` for Windows) | as above; `configDefaults` (shipped as `conf.defaults/`) |
| `appLibsArchive<Target>` | The libraries alone, `<app>-libs-<version>-<target>.tar.gz`, with `lib/` (Node: `app/`) at its root | your dependencies |
| `appRuntimeArchive<Target>` | The runtime alone, `<app>-runtime-<version>-<target>.tar.gz` (`.zip` for Windows), with `runtime/` at its root | as `jlink<Target>` and `customiseRuntime<Target>` |
| `appRuntimeFingerprint<Target>` | `<app>-runtime-<version>-<target>-inputs.txt`: what the runtime archive is built from, published beside it; see [Publishing](publishing.md#a-check-that-it-still-is) | |
| `installDist` | Lays out this machine's app with its runtime in `build/install/<app>` | as above |
| `install<Target>Dist` | The same for one target, e.g. `installLinuxX64Dist`, in `build/app/<target>/` | as above |
| `startScripts` | Java: Gradle's own task, writing `bin/<app>` and `bin/<app>.bat`, which start the embedded runtime | `runtimeDefaults`, `jvmArgs`, `application { applicationDefaultJvmArgs }`; `-PjavaApp.runtimeOptions` |
| `appLaunchers<Target>` | Node: writes the target's launcher | `entryPoint`, `nodeArgs`, `security { tlsMinVersion, tlsCipherList }`; `-PnodeApp.runtimeOptions` |
| `appManifest<Target>` | Writes `META-INF/app.json`: name, version, target, runtime, git commit | `appName` |
| `appSbom<Target>` | Writes `META-INF/sbom.cdx.json`, a CycloneDX SBOM including the runtime; skipped when you supply your own | `sbom = …` |
| `appVerifyPolicy<Target>` | Checks the assembled app against the organisation's runtime policy; every archive depends on it, and it is skipped when no policy is set | `securityPolicy`; `-PjavaApp.securityPolicy` (`nodeApp.…` for Node) |

### Running and checking

| Task | What it does | Settings |
|---|---|---|
| `appSmokeTest` | Unpacks this machine's app archive with its runtime and libraries archives, checks `version`, starts the app on a free port and waits for its health check; part of `check` | `smokeTestEnabled`; `-PjavaApp.smokeTestTimeout` |
| `runApp` | Runs this machine's packaged app in the foreground | `-Pargs="…"` (arguments for the app) |
| `checkRuntimeSupport` | Fails if the embedded runtime's release line has reached end of life; warns if it will within 90 days or a newer patch is out. Run it on a schedule; not part of `check`. See [Keeping the runtime patched](runtime-updates.md) | `-PjavaApp.runtimeSupportUrl`, `-PjavaApp.endOfLifeWarningDays`, `-PjavaApp.allowEndOfLife` (`nodeApp.…` for Node) |

In a deployment pipeline, the `app-smoke-test` plugin adds its own `appSmokeTest`, which smoke-tests a published release
declared as `smokeTest("com.example:orders-service:1.4.2")`, picking the archives built for the machine it runs on. It takes
`-PappSmokeTest.securityPolicy` to refuse an archive that breaks the runtime policy.

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
| `nodeTrustedCertificates` | Bundles trusted CAs and the policy's into `security/trusted-cas.pem`; writes nothing when there are none | `security { trustedCertificates }` |

### RPMs and images

| Task | What it does | Settings |
|---|---|---|
| `appRpm`, `appRpm<Target>` | Builds an RPM for each Linux target; part of `assemble` when enabled. RPMs are not published | `rpm { enabled, user, systemd }`, `appName`, `license`, the project's `description`; `-PjavaApp.rpm` |
| `appDockerfile` | Creates the project's root `Dockerfile` if missing and keeps its embedded-runtime block up to date; one without the block is left as written | |
| `appImageDockerfile<Name>` | Writes `Dockerfile.<name>` for the context from `images { register("<name>") }` | `images { … }` |
| `appImagePackagesLock` | Pins the RPMs of `image { packages(…) }` and every image for each Linux CPU in `rpms.lock.yaml`; run it when they change, and commit the file | `image { … }`, `images { install(…) }`, `-PbinaryRepo.repo.ubi` |
| `appImagePackages<Target>` | Fetches the RPMs the lock pins for one target, checked against its SHA-256, into the context's `packages/<cpu>/`; skipped when no image installs any | as above |
| `appDockerContext` | Writes a container build context: the Dockerfile and each `Dockerfile.<name>`, plus each Linux target's archives, unpacked as image trees under `layers/<cpu>/runtime/`, `layers/<cpu>/app/` and its two halves `libs/` and `code/` (and, for older Dockerfiles, under `runtime/<cpu>/` and `app/<cpu>/`), with fixed file times | `targets(…)`, `license`, `images { … }` |

### Publishing and the rest

| Task | What it does | Settings |
|---|---|---|
| `publish` | Gradle's own: publishes every target's app archive as `<app>`, libraries archive as `<app>-libs` and runtime archive as `<app>-runtime`, under one version, with the target as the classifier. Stops for archives never started on this machine | `-PbinaryRepo.repository` or `-PbinaryRepo.url`, `-PbinaryRepo.repo.releases`, `-PbinaryRepo.repo.snapshots`, `-PjavaApp.allowUntestedPublish` |
| `appVerifyRuntimeFingerprint` | Before `publish`, with `previousRelease` set: fails when a runtime's inputs equal that release's but its archive doesn't, so the repository would store it twice | `-PjavaApp.previousRelease` |
| `aiSkills` | Writes the AI assistant skills, the build-check hook and Devin's hook configuration into the project, and adds the Claude Code plugin to `.claude/settings.json`; part of `assemble` | `-PjavaApp.aiSkills` |

## Configuration

Gradle properties, set in `gradle.properties`, `~/.gradle/gradle.properties` or with `-P`. The repository and runtime ones can
also be set in `embeddedRuntime {}`, and `targets`, `rpm`, `securityPolicy` and the `appSmokeTest.` ones in `javaApp {}`,
`nodeApp {}` or `appSmokeTest {}` as above.

| Property | Default | |
|---|---|---|
| `javaApp.jdk.distribution` | `temurin` | JDK vendor |
| `javaApp.jdk.<feature>` | the plugin's pin (Temurin's LTS releases) | Exact JDK version, e.g. `javaApp.jdk.25=25.0.3` |
| `javaApp.jdk.source` | the binary repository's runtimes repository when one is configured, else the vendor's site | Where JDKs come from: an `https:` or `file:` URL (a mirror in the vendor's layout), an absolute path (a directory in the runtimes repository's layout, e.g. `/srv/jdks`), a repository's name beside the binary repository's (`jdks-local`), or `yum:` and a repository name or URL (`yum:yum-local/rhel/9`) for the JDK as an RPM package |
| `javaApp.jdk.pattern` | the vendor's, or the runtimes repository's layout | The files in `javaApp.jdk.source`, with the tokens below; with `yum:`, the package name (`jdk-[feature]` for Oracle, e.g. `java-[feature]-openjdk-devel`), whose version is the pin without its build |
| `javaApp.jdk.<feature>.<target>.path` | | One JDK, as a directory or archive, e.g. `javaApp.jdk.21.linux-x64.path` |
| `javaApp.jdk.reuseToolchain` | `true` | Embed the compile toolchain's JDK for this machine's target when it is exactly the pinned distribution and version |
| `nodeApp.node.version` | the plugin's pin | Exact Node.js version |
| `nodeApp.node.source` | the binary repository's runtimes repository when one is configured, else nodejs.org/dist | Where Node.js comes from: an `https:` or `file:` URL (a mirror in nodejs.org's layout), an absolute path, or a repository's name beside the binary repository's |
| `nodeApp.node.pattern` | nodejs.org's, or the runtimes repository's layout | The files in `nodeApp.node.source`, with the tokens below |
| `nodeApp.npm.registry` | registry.npmjs.org | npm registry when not using a binary repository |
| `nodeApp.npm.caFile` | | PEM file of corporate CAs for npm |
| `javaApp.targets`, `nodeApp.targets` | this machine | Comma-separated targets to build |
| `javaApp.securityPolicy`, `nodeApp.securityPolicy` | | The organisation's runtime policy: a directory or zip, or its `group:name:version` |
| `appSmokeTest.signerIdentity` | | In a build applying `app-smoke-test`: a regex every archive's Sigstore certificate identity must match, checked before it is started; see [Publishing](publishing.md#only-archives-your-release-workflow-signed) |
| `appSmokeTest.signerIssuer` | `https://token.actions.githubusercontent.com` | The OIDC issuer that identity must come from |
| `appSmokeTest.sigstoreTrustedRoot` | fetched from Sigstore | A copy of Sigstore's `trusted_root.json`, for machines that can't fetch it |
| `appSmokeTest.securityPolicy` | | In a build applying `app-smoke-test`: the policy every archive must comply with before it is started |
| `javaApp.rpm`, `nodeApp.rpm` | `false` | Also build an RPM per Linux target |
| `javaApp.runtimeOptions`, `nodeApp.runtimeOptions` | `open` | `locked` refuses to start while anything would add runtime options |
| `javaApp.smokeTestTimeout`, `nodeApp.smokeTestTimeout` | `60` | Seconds the started archive may take to get healthy, in `appSmokeTest` and `jlinkFindModules` |
| `javaApp.allowUntestedPublish`, `nodeApp.allowUntestedPublish` | `false` | Allow publishing an archive that was never started |
| `javaApp.previousRelease`, `nodeApp.previousRelease` | | The release, or `latest.release`, whose runtimes `appVerifyRuntimeFingerprint` compares this build's with, resolved from the build's repositories |
| `javaApp.gitCommit`, `nodeApp.gitCommit` | `git rev-parse HEAD` | The commit recorded in `META-INF/app.json`, for builds outside a checkout |
| `javaApp.aiSkills`, `nodeApp.aiSkills` | `true` | `false` stops `assemble` writing the AI assistant skills and hooks into the project |
| `javaApp.runtimeSupportUrl`, `nodeApp.runtimeSupportUrl` | `https://endoflife.date/api/v1/products/` | Where `checkRuntimeSupport` reads end-of-life data; a mirror behind a corporate network |
| `javaApp.endOfLifeWarningDays`, `nodeApp.endOfLifeWarningDays` | `90` | How early `checkRuntimeSupport` warns of an end of life |
| `javaApp.allowEndOfLife`, `nodeApp.allowEndOfLife` | `false` | `checkRuntimeSupport` warns instead of failing on an end-of-life runtime |
| `binaryRepo.repository` | found | The name of a repository the build declares (found when not set; `none` borrows nothing): its server and credentials are used for the runtime archives, npm, an image's RPMs and publishing, and the build's own repositories are kept |
| `binaryRepo.url` | | With no repository declared: download everything through this binary repository (Artifactory, Nexus, …), logging in with `binaryRepoUsername` / `binaryRepoPassword` |
| `binaryRepo.repo.maven` | `maven-virtual` | |
| `binaryRepo.repo.gradlePlugins` | `gradle-plugins-virtual` | |
| `binaryRepo.repo.npm` | `api/npm/npm-virtual` | |
| `binaryRepo.repo.runtimes` | `runtimes` | |
| `binaryRepo.repo.ubi` | `ubi-9` | RPMs for images: a remote of Red Hat's UBI 9 content, in its layout (`[arch]/baseos/os/`, `[arch]/appstream/os/`) |
| `binaryRepo.repo.releases`, `binaryRepo.repo.snapshots` | `libs-release-local`, `libs-snapshot-local` | |
| `binaryRepo.allowInsecureProtocol` | `false` | Allow `http://` (test instances only) |

A `pattern` spells targets with the tokens `[os]`, `[arch]`, `[rpmArch]`, `[target]` and `[classifier]`, plus Ivy's
`[organisation]`, `[module]`, `[revision]`, `[artifact]` and `[ext]`. A JDK pattern also spells the version with `[feature]`,
`[interim]` and `[update]`; a Node.js pattern outside nodejs.org's layout, with `[version]`.
