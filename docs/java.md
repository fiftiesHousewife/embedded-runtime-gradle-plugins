# Java: modules, hardening and JDKs

## Which JDK modules go in

The embedded runtime holds only the JDK modules your app uses: what `jdeps` finds in your code and
its dependencies, plus `additionalModules`. jdeps can't see modules loaded by name at runtime, such as
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

### Shared module sets

By default the runtime doesn't hold exactly those modules, but the smallest of a few fixed sets that
holds them all. Apps on the same set, JDK and target then ship byte-identical runtimes, so a
container registry stores that layer once and a node that already pulled it for one app pulls nothing
for the next. The plugin's sets, measured as Java 21 runtimes:

| Set | Holds | Size |
|---|---|---|
| `base` | `java.base`, `jdk.httpserver` | 30 MB |
| `service` | HTTP clients and servers, XML, JDBC, JNDI, TLS, `jdk.unsupported` | 38 MB |
| `framework` | `service` plus what Spring Boot, Quarkus and Micronaut use (`java.desktop`, `java.instrument`, JFR, JMX) | 54 MB |
| `server` | `framework` plus locales, Kerberos, PKCS#11, XML signatures | 66 MB |

An app no set covers, such as one needing a module only it uses, gets exactly its own modules. A set
holding a module in `security.forbiddenModules` is never chosen. `jlinkFindModules` always works on
the app's own modules, so it still reports what the app really needs.

Add or replace sets, or turn them off:

```kotlin
javaApp {
    moduleSets.put("batch", listOf("java.base", "java.sql", "java.logging", "jdk.unsupported"))
}
```

`moduleSets.set(mapOf(...))` replaces the plugin's sets with your own, and
`moduleSets.set(emptyMap())` links exactly the app's modules. Choose sets from the runtimes
you actually ship: each archive's `runtime/release` file lists its modules on its `MODULES=` line.
[Image layers and caching](caching.md) has heuristics for choosing them.

Every runtime is linked without debug information, man pages or headers, and compressed.

## Hardening the runtime

A JDK straight from a vendor is configured for the open internet. Your organisation's changes are
configuration in the build, applied to every target's runtime after `jlink`:

```kotlin
javaApp {
    security {
        trustedCertificates.from("security/certs")          // approved CAs, added to cacerts
        trustedCertificate(uri("https://pki.example.com/root-ca.pem"), sha256 = "9f86d0…")  // fetched, pinned
        properties.put("networkaddress.cache.ttl", "30")      // applied over the JDK's java.security
        disableAlgorithms("tls", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384")
        lockProperties = true                                // no -Djava.security.properties at runtime
    }
}
```

| Setting | Does |
|---|---|
| `trustedCertificates` | PEM or DER files (or directories of them) added to the runtime's `cacerts` |
| `trustedCertificate(uri, sha256)` | A certificate fetched by URI (`https:` or `file:`), refused unless its SHA-256 matches the pin; repository credentials are sent only to URIs under `binaryRepo.url` |
| `replaceDefaultTrust` | Trust only those certificates, instead of adding them to the JDK's own CAs |
| `properties` | Security properties set in `conf/security/java.security`; `properties.putAll(…)` sets many at once |
| `disableAlgorithms("tls" \| "certpath" \| "jar", …)` | Added to the JDK's `jdk.tls`, `jdk.certpath` or `jdk.jar.disabledAlgorithms` list, never replacing it |
| `lockProperties` | Sets `security.overridePropertiesFile=false` |
| `forbiddenModules` | Modules the runtime must never contain; default `jdk.jdwp.agent`, the remote debugger |

The example drops two CBC-mode suites, the kind a policy that allows only AEAD ciphers removes. Check what the pinned JDK already
disables before adding to the lists: the static-RSA `TLS_RSA_*` suites, for example, are disabled
by default from JDK 24, 21.0.10, 17.0.18 and 11.0.30.

Properties worth considering for your policy: `jdk.serialFilter` (which classes may be
deserialized), `networkaddress.cache.ttl` (how long DNS answers are cached),
`jdk.includeInExceptions` (whether host names and file paths appear in exception messages; the
default leaves them out), `ocsp.enable` (certificate revocation checking) and `securerandom.source`.

`trustedCertificates` takes any Gradle file source, so certificates can come from the repository,
a shared directory, or a versioned artifact in your binary repository. Anything not modelled here goes in
`src/deploy/runtime/`, which is copied over the runtime as it is (`runtimeOverlay` to change it).
Each runtime records what was changed, with every certificate's SHA-256 fingerprint, in
`runtime/conf/security/java-app-customisations.txt`.

## An organisation-wide runtime policy

`security {}` is the project's to set, so a project can also weaken it. To take that out of every
project's hands, publish a **runtime policy** once and point every build machine and CI agent at it:

```properties
# ~/.gradle/gradle.properties on each machine, or ORG_GRADLE_PROJECT_... on CI. A property here
# overrides the same property in a project, so a project cannot unset it.
# a zip in your binary repository, by group:name:version
javaApp.securityPolicy=com.acme.platform:java-runtime-policy:7
# or a directory or zip on disk: javaApp.securityPolicy=/shared/policy
```

The policy is a directory (or a zip of one) with `runtime-policy.properties` and, optionally,
`certificates/`. The same policy covers Node.js apps, through `nodeApp.securityPolicy` and the keys
in [your-app.md](your-app.md#the-organisations-runtime-policy):

```properties
id=acme-java-runtime
version=7
# security.overridePropertiesFile=false
lockProperties=true
# see "Runtime options" below
runtimeOptions=locked
forbiddenModules=jdk.jdwp.agent,jdk.management.agent
forbiddenJvmArgs=-javaagent,-agentlib,-agentpath,-Djava.security.properties,-Djdk.tls.
# package URL prefixes, matched against the SBOM
forbiddenLibraries=pkg:maven/org.bouncycastle/bctls
# a security property, exactly this value
set.networkaddress.cache.ttl=30
append.jdk.tls.disabledAlgorithms=TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384
```

It is enforced three times:

1. **Applied last.** `customiseRuntime` applies the policy after the project's own settings and
   overlay, so its certificates are trusted and its values win.
2. **Checked before any archive exists.** `appVerifyPolicy<Target>` reads the assembled app's real
   `java.security`, `cacerts`, module list, launcher and SBOM, and fails the build on any shortfall:
   a project that hasn't locked its runtime options, passes a forbidden JVM option or ships a
   forbidden library gets no archive.
3. **Checked again before rollout.** A deployment pipeline that smoke-tests a published release
   with the `app-smoke-test` plugin, and sets `appSmokeTest.securityPolicy`, refuses an archive
   that breaks the policy, whoever built it.

Each runtime records the policy's id, version and SHA-256 in
`runtime/conf/security/java-app-customisations.txt`.

What it cannot do is stop the application's own code: code can call `Security.setProperty`, bring
its own TLS provider or trust manager, or open sockets. `forbiddenLibraries` closes the commonest of
those doors; the rest belongs at the network boundary. See the article's section on corporate
control.

## Production JVM defaults

The JVM's own defaults suit few services: a quarter of the memory for the heap, the Serial
collector on a machine or container with fewer than 2 CPUs or under about 1.8 GB, and heap dumps,
when switched on, in whatever directory the app started in. Every launcher therefore starts the
JVM with production defaults:

| Option | Does |
|---|---|
| `-XX:MaxRAMPercentage=75` | Three quarters of the memory for the heap |
| `-XX:+ExitOnOutOfMemoryError` | Exits on `OutOfMemoryError`, so a supervisor restarts the app |
| `-XX:+HeapDumpOnOutOfMemoryError` | A heap dump, and `hs_err` crash logs, in `$APP_LOG_DIR` |

They come ahead of the project's `jvmArgs`, so any of them can be overridden there, and anything
else goes there too:

```kotlin
javaApp {
    jvmArgs.addAll("-XX:MaxRAMPercentage=85", "-XX:+DisableAttachMechanism")   // more heap; no jcmd, jstack or agents
    // runtimeDefaults = false                                                 // the JVM's own defaults
}
```

The launcher creates `$APP_LOG_DIR` with mode 750 when it is missing, and the JVM writes heap dumps
readable by the app's user only, since they hold whatever was in memory: tokens, passwords,
personal data. On `OutOfMemoryError` the app writes its dump and exits with code 3. The defaults
leave the collector to the JVM, because an operator who chooses one in `APP_JAVA_OPTS` would stop
the JVM from starting if the build had chosen one too (two collectors conflict). Choose one in
`jvmArgs` (`-XX:+UseG1GC`) when operators won't, or when the runtime's options are locked.

## Runtime options

`-PjavaApp.runtimeOptions=locked` makes the launcher refuse to start
while anything would add JVM options the build did not fix: `APP_JAVA_OPTS`, `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, or a `jvm.options` file in the configuration directory. The
default, `open`, adds `$APP_CONF_DIR/jvm.options` (a JVM argument file) and then `$APP_JAVA_OPTS`
after the build's `jvmArgs`. Both the build's `jvmArgs` and `jvm.options` are taken literally: a
`$VARIABLE` in them is not expanded, so give paths in full.

## Choosing a JDK

```properties
# gradle.properties
javaApp.jdk.distribution=oracle
javaApp.jdk.25=25.0.3
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

Each plugin release pins an exact, tested Temurin build of each long-term-support release (11, 17,
21 and 25), and the same JDK compiles your code and runs inside the archive. Any other distribution,
or a release in between, takes its exact version from the build, as above: the build stops and says
what to set when one is missing, and a version in the wrong form for its distribution is rejected
before anything is downloaded. The same property overrides a Temurin pin, to take a security patch
before the next plugin release; [Renovate presets](renovate.md) keep such versions current.

Not every distribution covers every release and target:

| Distribution | Gap |
|---|---|
| `microsoft` | Long-term-support releases only: 11, 17, 21 and 25 |
| `temurin` | No Intel Mac (`mac-x64`) build of 27. From 24, `jmods/` is a separate download, which the plugin fetches beside the JDK |
| `sapmachine` | No Intel Mac build from 25. From 24 it ships no `jmods/` at all, so each target's runtime can only be linked on that target's own OS and CPU: build it there, or use another distribution |

A Java 24 or later JDK without `jmods/` (JEP 493) can still link a runtime for the machine it runs
on, but not for another one, which is why a cross build needs the jmods.

> **Oracle licensing.** Whether you may redistribute an Oracle runtime inside your archive is
> decided by Oracle's licence, not by these plugins. Recent releases under the NFTC allow it;
> OTN-licensed releases do not. The other distributions are OpenJDK builds under GPLv2 with the
> Classpath Exception.

To download from a mirror, a directory or your binary repository instead, set `javaApp.jdk.source`:
see [Where JDKs and Node.js come from](binary-repository.md#where-jdks-and-nodejs-come-from).

### The compile toolchain's JDK

The plugin sets `java.toolchain` to the pinned release and vendor, so Gradle compiles with that JDK,
installed on the machine or provisioned. When that JDK is exactly the pinned one (the same
distribution, by the `IMPLEMENTOR` in its `release` file, and the same version) and jlink can link
from it (it has `jmods/`, or the vendor ships those separately), this machine's archive embeds it
directly: nothing more is downloaded or unpacked for this machine's target. Other targets are
fetched as usual. The JDK running the Gradle daemon plays no part. Set
`javaApp.jdk.reuseToolchain=false` (or `embeddedRuntime { jdk { reuseToolchain = false } }`) to
always use the configured source instead.

### JDKs already on disk

To use JDKs you already have instead of downloading them, point at a shared directory of archives,
laid out like the [binary repository's runtimes repository](binary-repository.md#repositories-to-create):

```properties
# /shared/jdks/oracle/jdk/25.0.3/jdk-25.0.3-linux-x64.tar.gz
javaApp.jdk.source=/shared/jdks
```

or configure one JDK at a time, as an installed directory or an archive:

```properties
javaApp.jdk.21.linux-x64.path=/usr/lib/jvm/jdk-21
javaApp.jdk.21.windows-x64.path=/shared/jdks/jdk-21-windows.zip
```

A JDK configured this way wins over every other source for that release and target, so it also
covers JDKs installed from your own RPMs; an `.rpm` file itself is unpacked and the JDK found inside
it. On Windows write the path with forward slashes (`C:/Program Files/Java/jdk-21`), because a
backslash in `gradle.properties` escapes the next character. The JDK used to run `jlink` (the one for
the machine you build on) and each target's JDK must be the same exact version, and that version
must be the one pinned: a JDK a patch behind stops the build. Gradle compiles with a zip or tar.gz
configured this way directly; for an installed directory or an RPM, it uses an installed JDK, so
also list it in `org.gradle.java.installations.paths` if Gradle doesn't find it on its own.

JDK RPMs in a binary repository, under your own naming, are covered in
[Behind a corporate binary repository](binary-repository.md#jdks-packaged-as-rpms).
