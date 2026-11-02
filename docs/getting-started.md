# Getting started

The [README](../README.md#quick-start) has the smallest build. This page has a complete one for each
ecosystem. Everything in them is optional, and each block says what it changes.

## A complete Java service

Linux on x64 and ARM plus Windows, a JDK trimmed to what the app uses, the organisation's CAs and TLS rules, an
image on UBI micro, an RPM, and publication:

```kotlin
// build.gradle.kts
plugins {
    id("io.github.fiftieshousewife.java-app")
}

group = "com.example"
version = "1.4.2"
description = "Order service"                  // the RPM's summary

application {
    mainClass = "com.example.orders.Main"
}

javaApp {
    appName = "orders-service"                 // launcher, archive and RPM name; default: the project name

    // Target platforms: app, libraries and runtime archives each, zip for Windows and tar.gz for Linux. A Mac or Windows
    // machine builds the Linux ones directly, with no containers. Only this machine's archives can be
    // started, so only they are smoke-tested.
    targets("linux-x64", "linux-aarch64", "windows-x64")

    // The runtime: compiled with and embedded. The vendor and exact version are Gradle properties,
    // below; the plugin pins a version only for Temurin's LTS releases.
    javaVersion = 25                           // 11 to 27

    // jlink links the smallest shared module set holding what jdeps finds, plus these, which code loads
    // by name at runtime (`./gradlew jlinkFindModules` finds them by running the app).
    additionalModules = listOf("jdk.localedata")

    // How the JVM starts. By default it gets 75% of the machine's or container's memory, exits on
    // OutOfMemoryError and leaves a heap dump and crash logs in $APP_LOG_DIR; jvmArgs come after
    // these, so they win.
    jvmArgs = listOf("-Xss512k", "-XX:+UseG1GC", "-XX:+DisableAttachMechanism")

    // CAs and encryption, applied to every target's runtime after jlink.
    security {
        trustedCertificates.from("security/certs")                    // PEM or DER, added to cacerts
        trustedCertificate(uri("https://pki.example.com/root-ca.pem"), sha256 = "9f86d081884c7d65…")
        disableAlgorithms(                                            // added to the JDK's own list
            "tls",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        )
        properties.put("networkaddress.cache.ttl", "30")              // any java.security property
        lockProperties = true                                         // no -Djava.security.properties
    }

    // An image with no Dockerfile to write: on micro, with zlib added for you. Run appImagePackagesLock once and
    // commit rpms.lock.yaml. On a corporate network, from("<your mirror>/ubi9/ubi-micro:<tag>@sha256:…").
    images { register("ubi-micro") { from(ubiMicro) } }

    // Packaging beyond the archives.
    license = "LicenseRef-Proprietary"         // SPDX: the image label and the RPM's licence
    configDefaults = file("src/deploy/conf")   // shipped as conf.defaults/ and an RPM's /etc/<name>/
    rpm {
        enabled = true                         // an RPM per Linux target, installed under /opt
        systemd = true                         // a hardened systemd unit, run as its own user
    }
}
```

Put the machine's settings in `gradle.properties` or `~/.gradle/gradle.properties`, or pass them as
`ORG_GRADLE_PROJECT_…` variables in CI:

```properties
# the JDK vendor, and its exact version
javaApp.jdk.distribution=oracle
javaApp.jdk.25=25.0.3
# the repository settings.gradle.kts already declares: its server and credentials are used for
# runtimes, npm, RPMs and publishing (see "Behind a corporate binary repository")
binaryRepo.repository=artifactory
# the organisation's runtime policy
javaApp.securityPolicy=com.example.platform:java-runtime-policy:7
# the launcher refuses to start while JAVA_TOOL_OPTIONS, APP_JAVA_OPTS or a jvm.options file
# would add options of their own
javaApp.runtimeOptions=locked
# publish the targets this machine couldn't start
javaApp.allowUntestedPublish=true
# how long, in seconds, the started archive may take to get healthy
javaApp.smokeTestTimeout=90
```

`./gradlew build` then gives app, libraries and runtime archives for each of the three targets in
`build/distributions/`, and two RPMs. `publish` uploads the archives as three modules under one version, `<app>`,
`<app>-libs` and `<app>-runtime`, with the target as the classifier; the RPMs are not published. The runtime policy
is checked before any archive is built. `appDockerContext` writes `build/docker-context/`, from which your CI builds
the image.

Next: [Building for Linux, Mac and Windows](targets.md), [Java: modules, hardening and JDKs](java.md),
[Packaging and deploying](packaging.md), [Testing the container image](container-testing.md).

## A Node.js service

Next to `package.json` and `package-lock.json`, with the `node-app` settings plugin in `settings.gradle.kts`:

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

    targets("linux-x64", "linux-aarch64")
    installScripts = listOf("esbuild")         // vetted packages whose install scripts run; default: none

    nodeArgs = listOf("--enable-source-maps", "--max-old-space-size=512")

    // CAs and encryption: the CAs go into security/trusted-cas.pem, which the launcher passes to
    // Node as NODE_EXTRA_CA_CERTS; the TLS settings become node options.
    security {
        trustedCertificates.from("security/certs")
        tlsMinVersion = "1.3"
        tlsCipherList = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"
    }
}
```

The Node.js version is `nodeApp.node.version` (default: the plugin's pin), and `nodeApp.runtimeOptions=locked` also
refuses `NODE_OPTIONS` and `NODE_TLS_REJECT_UNAUTHORIZED`. Stay on UBI minimal, as the official builds need
`libstdc++`, which micro leaves out, and handle SIGTERM yourself ([Stopping](packaging.md#stopping)). See
[What your app needs to do](your-app.md#nodejs-trusted-cas-and-tls) and
[Behind a corporate binary repository](binary-repository.md) for npm registries and CAs.

## Checklist before production

| | Where |
|---|---|
| The app reads `APP_PORT`, answers `--healthcheck` and stops on SIGTERM | [Your app](your-app.md) |
| Modules loaded by name are in the runtime: TLS providers, JNDI, `jdk.localedata` | [Java](java.md#which-jdk-modules-go-in) |
| Every download goes through the binary repository, and base images are your mirror's, by digest | [Binary repository](binary-repository.md) |
| The image is tested hardened, under its memory limit | [Testing the container image](container-testing.md#what-goes-wrong-in-containers) |
| Memory and CPU come from a measurement | [Capacity](capacity.md) |
| A test fails when the runtime or libraries stop repeating | [Caching](caching.md#tips-for-caching-more) |
| `checkRuntimeSupport` runs on a schedule | [Patching](runtime-updates.md) |
