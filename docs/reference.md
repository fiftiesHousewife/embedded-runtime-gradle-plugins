# Reference

## `javaApp {}` and `nodeApp {}`

```kotlin
javaApp {                                       // nodeApp {} has the same first nine settings
    appName = "orders-service"                  // launcher and archive name; default: project name
    targets("linux-x64", "windows-x64")         // default: -PjavaApp.targets, else this machine
    archiveFormat("tar.gz")                     // or "tar", "zip"; default: -PjavaApp.archiveFormat
    rpm {                                       // see Packaging and deploying
        enabled = false                         // default: -PjavaApp.rpm
        packageName = "orders-service"          // default: appName
        release = "1"
        summary = "…"                           // default: "<app> with its own runtime"
        license = "LicenseRef-Proprietary"      // default: the app's license
        vendor = "Example Ltd"
        url = "https://example.com/orders"
        user = "orders-service"                 // service account; default: packageName
        systemd = true                          // install and manage a systemd unit
        repositoryUrl = uri("https://…/rpm-local") // default: binaryRepo.repo.rpm
    }
    license = "Apache-2.0"                      // SPDX; default LicenseRef-Proprietary (image label, RPM)
    sourceUrl = "https://github.com/example/orders" // image label; default: the origin remote
    dockerfile = file("docker/Dockerfile")      // default: Dockerfile, else src/deploy/docker/Dockerfile
    configDefaults = file("src/deploy/conf")    // shipped as conf.defaults/
    sbom = file("build/reports/bom.json")       // your own CycloneDX SBOM instead of the built-in one
    securityPolicy = "/etc/acme/java-policy"    // a directory or zip; -PjavaApp.securityPolicy wins over this
    securityPolicyArtifact = "com.acme:java-runtime-policy:7" // or the policy's zip by coordinates
    gitCommit = "902c348"                       // default: -PjavaApp.gitCommit, else git rev-parse HEAD
    runtimeSupport {                            // checkRuntimeSupport
        url = "https://endoflife.date/api/v1/products/" // default: -PjavaApp.runtimeSupportUrl
        warningDays = 90                        // default: -PjavaApp.endOfLifeWarningDays
        allowEndOfLife = false                  // default: -PjavaApp.allowEndOfLife
    }
    smokeTestEnabled = true                     // start the archive during `check`
    smokeTestTimeout = Duration.ofSeconds(60)
    allowUntestedPublish = false

    javaVersion = 21                            // 11 to 27
    jdkVersion = "21.0.13+9"                    // default: the plugin's pin
    additionalModules = listOf("jdk.crypto.ec") // added to what jdeps finds
    jlinkModules = listOf("java.base")          // instead: exactly these modules
    runtimeOverlay = file("src/deploy/runtime") // copied over the runtime as it is
    security { … }                              // see Hardening the runtime
    jlinkOptions = listOf("--strip-debug", "--no-man-pages", "--no-header-files", "--compress=zip-6")
    runtimeDefaults {                           // production JVM options, ahead of jvmArgs
        enabled = true
        maxRamPercentage = 75
        exitOnOutOfMemory = true
        heapDumpOnOutOfMemory = true            // dumps and hs_err files in $APP_LOG_DIR
        garbageCollector = "G1"                 // default unset: the JVM chooses
        disableAttach = false
    }
    jvmArgs = listOf("-Xss512k")                // after runtimeDefaults, before $APP_JAVA_OPTS
}

nodeApp {
    nodeVersion = "24.22.1"                     // default: the plugin's pin
    entryPoint = "main.js"                      // under app/; started by `run`
    appFiles = files("dist")                    // default: src/ (minus src/deploy/), or dist/ with buildScript
    buildScript = "build"                       // npm script run before packaging
    testScript = "test"                         // npm script run by `check`, if package.json has it
    ignoreInstallScripts = true                 // npm --ignore-scripts for production dependencies
    portableInstallScripts = listOf("esbuild")  // vetted packages that don't block cross builds
    nodeArgs = listOf("--enable-source-maps")   // before $APP_NODE_OPTS
    security {
        trustedCertificates.from("certs")       // PEM or DER CAs, added to Node's own via NODE_EXTRA_CA_CERTS
        tlsMinVersion = "1.3"                   // --tls-min-v1.3; "1.2" or "1.3"
        tlsCipherList = "TLS_AES_256_GCM_SHA384" // --tls-cipher-list
    }
}
```

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
        pin(21, "21.0.5")                       // javaApp.jdk.21
        yumRepo = "yum-local"                   // javaApp.jdk.yumRepo; and yumPackage
        binaryRepo = "generic-local"            // javaApp.jdk.binaryRepo; and binaryRepoPattern,
        revisionFormat = "[feature]_[update]"   // revisionFormat, url, pattern, localPath, localPattern
        path(21, "windows-x64", "C:/Program Files/Java/jdk-21") // javaApp.jdk.21.windows-x64.path
        token("plat", mapOf("linux" to "l64", "windows" to "w64")) // javaApp.jdk.token.plat.linux, …
    }
    node {
        version = "24.22.1"                     // nodeApp.node.version; and url, pattern, binaryRepoPattern,
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
| `appRpm` | Builds each Linux target's RPM (`appRpmLinuxX64`, …), when enabled; part of `assemble` |
| `appPublishRpm` | Uploads the RPMs to `rpm.repositoryUrl`; part of `publish` when that is set |
| `appSmokeTest` | Starts this machine's archive and waits for its health check; part of `check` |
| `runApp` | Runs this machine's packaged app; pass arguments with `-Pargs="…"` |
| `appVerifyPolicyLinuxX64`, … | Checks an assembled app against the runtime policy; every archive depends on it |
| `appDockerfile` | Writes the Dockerfile: the project's own (`Dockerfile`, `src/deploy/docker/Dockerfile` or `dockerfile`) if there is one, else a generated one |
| `appDockerContext` | Writes a container build context: that Dockerfile plus each Linux archive under `app/amd64/` or `app/arm64/` |
| `checkRuntimeSupport` | Fails if the embedded runtime's release line has reached end of life; warns if it will within 90 days or a newer patch is out. Not part of `check`; see [Keeping the runtime patched](runtime-updates.md) |
| `downloadJdk<Target>` | Java, with `javaApp.jdk.yumRepo`: the JDK package from the YUM repository, found in its metadata and checked against its SHA-256 |
| `jdeps` | Java: lists the JDK modules the code uses |
| `jlinkFindModules` | Java: finds the modules jdeps can't see, by running the app |
| `jlinkLinuxX64`, … | Java: builds one target's trimmed runtime |
| `customiseRuntimeLinuxX64`, … | Java: applies `security {}` and the overlay to it |
| `npmInstall`, `npmBuild`, `npmTest` | Node: `npm ci`, your build script, your test script |
| `nodeTrustedCertificates` | Node: bundles `security { trustedCertificates }` and the runtime policy's certificates into `security/trusted-cas.pem`; writes nothing when there are none |

## Configuration

Gradle properties, set in `gradle.properties`, `~/.gradle/gradle.properties` or with `-P`. Each can
also be set in code: the repository and runtime ones in `embeddedRuntime {}`, the rest in
`javaApp {}`, `nodeApp {}` or `appSmokeTest {}` as above.

| Property | Default | |
|---|---|---|
| `javaApp.jdk.distribution` | `temurin` | JDK vendor |
| `javaApp.jdk.<feature>` | the plugin's pin | Exact JDK version, e.g. `javaApp.jdk.21=21.0.13+9` |
| `javaApp.jdk.url`, `javaApp.jdk.pattern` | the vendor's site | Download JDKs from a mirror |
| `javaApp.jdk.localPath`, `javaApp.jdk.localPattern` | | Read JDK archives from a shared directory |
| `javaApp.jdk.<feature>.<target>.path` | | One JDK, as a directory or archive, e.g. `javaApp.jdk.21.linux-x64.path` |
| `nodeApp.node.version` | the plugin's pin | Exact Node.js version |
| `nodeApp.node.url`, `nodeApp.node.pattern` | nodejs.org/dist | Download Node.js from a mirror |
| `nodeApp.npm.registry` | registry.npmjs.org | npm registry when not using a binary repository |
| `nodeApp.npm.caFile` | | PEM file of corporate CAs for npm |
| `javaApp.targets`, `nodeApp.targets` | this machine | Comma-separated targets to build |
| `javaApp.securityPolicy`, `javaApp.securityPolicyArtifact`, `nodeApp.securityPolicy`, `nodeApp.securityPolicyArtifact` | | The organisation's runtime policy: a directory or zip, or its `group:name:version` |
| `appSmokeTest.signerIdentity` | | In a build applying `app-smoke-test`: a regex every archive's Sigstore certificate identity must match, checked before it is started; see [Publishing](publishing.md#only-archives-your-release-workflow-signed) |
| `appSmokeTest.signerIssuer` | `https://token.actions.githubusercontent.com` | The OIDC issuer that identity must come from |
| `appSmokeTest.sigstoreTrustedRoot` | fetched from Sigstore | A copy of Sigstore's `trusted_root.json`, for machines that can't fetch it |
| `appSmokeTest.securityPolicy`, `appSmokeTest.securityPolicyArtifact` | | In a build applying `app-smoke-test`: the policy every archive must comply with before it is started |
| `javaApp.archiveFormat`, `nodeApp.archiveFormat` | `tar.gz` | `tar.gz`, `tar` or `zip` |
| `javaApp.rpm`, `nodeApp.rpm` | `false` | Also build an RPM per Linux target |
| `javaApp.runtimeOptions`, `nodeApp.runtimeOptions` | `open` | `locked` refuses to start while anything would add runtime options |
| `javaApp.allowUntestedPublish`, `nodeApp.allowUntestedPublish` | `false` | Allow publishing an archive that was never started |
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
| `binaryRepo.repo.rpm` | `rpm-local` | Where `appPublishRpm` uploads |
| `javaApp.jdk.binaryRepoPattern`, `nodeApp.node.binaryRepoPattern` | the layout above | Layout of an existing runtimes tree; a JDK pattern ending in `.rpm` reads JDK RPMs |
| `javaApp.jdk.yumRepo` | | A YUM repository holding the JDK as an RPM package: a repository beside the binary repository (`yum-local`, `yum-local/rhel/9`) or a full URL; the package is found in its metadata by name, pinned version and CPU |
| `javaApp.jdk.yumPackage` | `jdk-[feature]` for Oracle | The JDK package's name in `javaApp.jdk.yumRepo` |
| `javaApp.jdk.binaryRepo` | `binaryRepo.repo.runtimes` | The repository holding JDKs, e.g. `yum-local` |
| `javaApp.jdk.reuseToolchain` | `true` | Embed the compile toolchain's JDK for this machine's target when it is exactly the pinned distribution and version |
| `javaApp.jdk.token.<name>.<os or target>` | | A pattern token of your own, e.g. `javaApp.jdk.token.plat.linux=l64` for `[plat]`; `nodeApp.node.token.…` for Node.js |
| `javaApp.jdk.revisionFormat` | `[underscored]` | How a curated pattern's `[revision]` spells the version: `[feature]_[update]` gives `21_5` for 21.0.5 |
| `binaryRepo.allowInsecureProtocol` | `false` | Allow `http://` (test instances only) |
