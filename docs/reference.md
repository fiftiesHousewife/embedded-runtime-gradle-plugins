# Reference

## `javaApp {}` and `nodeApp {}`

```kotlin
javaApp {                                       // nodeApp {} has the same first eight settings
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

    javaVersion = 25                            // 11 to 27
    additionalModules = listOf("jdk.crypto.ec") // added to what jdeps finds
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

Every archive is a zip for Windows and a tar.gz for Linux and macOS. The Dockerfile is the project's
root `Dockerfile`. The image label's source and the RPM's URL are the `origin` git remote. An RPM is
named after `appName`, with release `1`, the app's `license`, and the project's `description` as its
summary (default: "<app> with its own runtime").

## `embeddedRuntime {}` in `settings.gradle.kts`

Everything the settings plugins read, set in code. Each setting stands for the Gradle property in
its comment and wins over it; whatever is left unset still comes from the properties, so settings
in `~/.gradle/gradle.properties` keep working.

```kotlin
embeddedRuntime {
    binaryRepository {
        repository = "artifactory"              // binaryRepo.repository: borrow a repository the build declares
        url = "https://repo.example.com"        // binaryRepo.url
        mavenRepo = "maven-virtual"             // binaryRepo.repo.maven; likewise gradlePluginsRepo,
        runtimesRepo = "runtimes"               // runtimesRepo, releasesRepo, snapshotsRepo, rpmRepo, npmRepo
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

| Task | Does |
|---|---|
| `appArchive` | Builds every target's archive; part of `assemble` |
| `install<Target>Dist`, `<target>DistTar`, `<target>DistZip` | The distribution plugin's tasks for each target's distribution, e.g. `installLinuxX64Dist` |
| `installDist` | Installs this machine's app with its runtime into `build/install/<app>` |
| `startScripts` | Gradle's own task; writes the Java launchers with the embedded-runtime script generator |
| `appRpm` | Builds each Linux target's RPM (`appRpmLinuxX64`, …), when enabled; part of `assemble`. `publish` uploads them when there is a YUM repository |
| `appSmokeTest` | Starts this machine's archive and waits for its health check; part of `check` |
| `runApp` | Runs this machine's packaged app; pass arguments with `-Pargs="…"` |
| `appVerifyPolicyLinuxX64`, … | Checks an assembled app against the runtime policy; every archive depends on it |
| `appDockerfile` | Creates the project's `Dockerfile` if missing and keeps its embedded-runtime block up to date; one without the block is left as written |
| `appDockerContext` | Writes a container build context: the Dockerfile and each `Dockerfile.<name>`, plus each Linux archive, its runtime under `runtime/<cpu>/` and the rest under `app/<cpu>/` |
| `checkRuntimeSupport` | Fails if the embedded runtime's release line has reached end of life; warns if it will within 90 days or a newer patch is out. Not part of `check`; see [Keeping the runtime patched](runtime-updates.md) |
| `downloadJdk<Target>` | Java, with `javaApp.jdk.source=yum:…`: the JDK package from the YUM repository, found in its metadata and checked against its SHA-256 |
| `jlinkFindModules` | Java: finds the modules jdeps can't see, by running the app |
| `jlinkLinuxX64`, … | Java: builds one target's trimmed runtime |
| `customiseRuntimeLinuxX64`, … | Java: applies `security {}` and the overlay to it |
| `npmInstall`, `npmBuild`, `npmTest` | Node: `npm ci`, your build script, your test script |
| `aiSkills` | Writes the AI assistant skills, the build-check hook and Devin's hook configuration into the project, and adds the Claude Code plugin to `.claude/settings.json`; part of `assemble` |
| `nodeTrustedCertificates` | Node: bundles `security { trustedCertificates }` and the runtime policy's certificates into `security/trusted-cas.pem`; writes nothing when there are none |

## Configuration

Gradle properties, set in `gradle.properties`, `~/.gradle/gradle.properties` or with `-P`. The
repository and runtime ones can also be set in `embeddedRuntime {}`, and `targets`, `rpm`,
`securityPolicy` and the `appSmokeTest.` ones in `javaApp {}`, `nodeApp {}` or `appSmokeTest {}` as
above.

| Property | Default | |
|---|---|---|
| `javaApp.jdk.distribution` | `temurin` | JDK vendor |
| `javaApp.jdk.<feature>` | the plugin's pin (Temurin's LTS releases) | Exact JDK version, e.g. `javaApp.jdk.25=25.0.3` |
| `javaApp.jdk.source` | the binary repository's runtimes repository when one is configured, else the vendor's site | Where JDKs come from: an `https:` or `file:` URL (a mirror in the vendor's layout), an absolute path (a directory in the runtimes repository's layout, e.g. `/srv/jdks`), a repository's name beside the binary repository's (`jdks-local`), or `yum:` and a repository name or URL (`yum:yum-local/rhel/9`) for the JDK as an RPM package |
| `javaApp.jdk.pattern` | the vendor's, or the runtimes repository's layout | The files in `javaApp.jdk.source`, with the tokens below; with `yum:`, the package name (`jdk-[feature]` for Oracle, e.g. `java-[feature]-openjdk-devel`), whose version is the pin without its build |
| `javaApp.jdk.<feature>.<target>.path` | | One JDK, as a directory or archive, e.g. `javaApp.jdk.21.linux-x64.path` |
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
| `javaApp.rpmRepositoryUrl`, `nodeApp.rpmRepositoryUrl` | `binaryRepo.repo.rpm` | A YUM repository accepting HTTP PUT, where `publish` uploads the RPMs |
| `javaApp.runtimeOptions`, `nodeApp.runtimeOptions` | `open` | `locked` refuses to start while anything would add runtime options |
| `javaApp.smokeTestTimeout`, `nodeApp.smokeTestTimeout` | `60` | Seconds the started archive may take to get healthy, in `appSmokeTest` and `jlinkFindModules` |
| `javaApp.allowUntestedPublish`, `nodeApp.allowUntestedPublish` | `false` | Allow publishing an archive that was never started |
| `javaApp.gitCommit`, `nodeApp.gitCommit` | `git rev-parse HEAD` | The commit recorded in `META-INF/app.json`, for builds outside a checkout |
| `javaApp.aiSkills`, `nodeApp.aiSkills` | `true` | `false` stops `assemble` writing the AI assistant skills and hooks into the project |
| `javaApp.runtimeSupportUrl`, `nodeApp.runtimeSupportUrl` | `https://endoflife.date/api/v1/products/` | Where `checkRuntimeSupport` reads end-of-life data; a mirror behind a corporate network |
| `javaApp.endOfLifeWarningDays`, `nodeApp.endOfLifeWarningDays` | `90` | How early `checkRuntimeSupport` warns of an end of life |
| `javaApp.allowEndOfLife`, `nodeApp.allowEndOfLife` | `false` | `checkRuntimeSupport` warns instead of failing on an end-of-life runtime |
| `binaryRepo.repository` | found | The name of a repository the build declares (found when not set; `none` borrows nothing): its server and credentials are used for the runtime archives, npm, publishing and uploads, and the build's own repositories are kept |
| `binaryRepo.url` | | With no repository declared: download everything through this binary repository (Artifactory, Nexus, …), logging in with `binaryRepoUsername` / `binaryRepoPassword` |
| `binaryRepo.repo.maven` | `maven-virtual` | |
| `binaryRepo.repo.gradlePlugins` | `gradle-plugins-virtual` | |
| `binaryRepo.repo.npm` | `api/npm/npm-virtual` | |
| `binaryRepo.repo.runtimes` | `runtimes` | |
| `binaryRepo.repo.releases`, `binaryRepo.repo.snapshots` | `libs-release-local`, `libs-snapshot-local` | |
| `binaryRepo.repo.rpm` | `rpm-local` | Where `publish` uploads the RPMs |
| `javaApp.jdk.reuseToolchain` | `true` | Embed the compile toolchain's JDK for this machine's target when it is exactly the pinned distribution and version |
| `binaryRepo.allowInsecureProtocol` | `false` | Allow `http://` (test instances only) |

A `pattern` spells targets with the tokens `[os]`, `[arch]`, `[rpmArch]`, `[target]` and
`[classifier]`, plus Ivy's `[organisation]`, `[module]`, `[revision]`, `[artifact]` and `[ext]`. A JDK
pattern also spells the version with `[feature]`, `[interim]` and `[update]`; a Node.js pattern outside
nodejs.org's layout, with `[version]`.
