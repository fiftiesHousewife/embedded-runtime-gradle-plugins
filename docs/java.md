# Java: modules, hardening and JDKs

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

`jlinkOptions` passes options straight to `jlink`. Besides the defaults (no debug information, man
pages or headers; compressed), some are worth knowing: `--include-locales=en,de` keeps only the
locales you need when `jdk.localedata` is linked, `--dedup-legal-notices=error-if-not-same-content`
removes duplicate licence files, and on Java 21 and later `--generate-cds-archive` speeds up startup
for an archive built on its own target.

## Hardening the runtime

A JDK straight from a vendor is configured for the open internet. Your organisation's changes are
configuration in the build, applied to every target's runtime after `jlink`:

```kotlin
javaApp {
    security {
        trustedCertificates.from("security/certs")          // approved CAs, added to cacerts
        propertiesFiles.from("security/java.security")      // your policy, applied over the JDK's
        properties.put("networkaddress.cache.ttl", "30")
        disabledTlsAlgorithms.addAll("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384")
        lockProperties = true                                // no -Djava.security.properties at runtime
    }
}
```

| Setting | Does |
|---|---|
| `trustedCertificates` | PEM or DER files (or directories of them) added to the runtime's `cacerts` |
| `replaceDefaultTrust` | Trust only those certificates, instead of adding them to the JDK's own CAs |
| `propertiesFiles`, `properties` | Security properties set in `conf/security/java.security`, files first |
| `disabledTlsAlgorithms`, `disabledCertPathAlgorithms`, `disabledJarAlgorithms` | Added to the JDK's own lists, never replacing them |
| `lockProperties` | Sets `security.overridePropertiesFile=false` |
| `forbiddenModules` | Modules the runtime must never contain; default `jdk.jdwp.agent`, the remote debugger |

The example drops two CBC-mode suites, the kind a policy that allows only AEAD ciphers removes. Check what the pinned JDK already
disables before adding to the lists: the static-RSA `TLS_RSA_*` suites, for example, are disabled
by default from JDK 24, 21.0.10, 17.0.18 and 11.0.30.

Properties worth considering for your policy file: `jdk.serialFilter` (which classes may be
deserialized), `networkaddress.cache.ttl` (how long DNS answers are cached),
`jdk.includeInExceptions` (whether host names and file paths appear in exception messages; the
default leaves them out), `ocsp.enable` (certificate revocation checking) and `securerandom.source`.

Every setting takes any Gradle file source, so certificates and policy can come from the repository,
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
javaApp.securityPolicyArtifact=com.acme.platform:java-runtime-policy:7      # a zip in your binary repository
# or javaApp.securityPolicy=/shared/policy                                  # a directory or zip on disk
```

The policy is a directory (or a zip of one) with `runtime-policy.properties` and, optionally,
`certificates/`:

```properties
id=acme-java-runtime
version=7
lockProperties=true                                  # security.overridePropertiesFile=false
runtimeOptions=locked                                # see "Runtime options" below
forbiddenModules=jdk.jdwp.agent,jdk.management.agent
forbiddenJvmArgs=-javaagent,-agentlib,-agentpath,-Djava.security.properties,-Djdk.tls.
forbiddenLibraries=pkg:maven/org.bouncycastle/bctls  # package URL prefixes, matched against the SBOM
set.networkaddress.cache.ttl=30                      # a security property, exactly this value
append.jdk.tls.disabledAlgorithms=TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384
```

It is enforced three times:

1. **Applied last.** `customiseRuntime` applies the policy after the project's own settings and
   overlay, so its certificates are trusted and its values win.
2. **Checked before any archive exists.** `appVerifyPolicy<Target>` reads the assembled app's real
   `java.security`, `cacerts`, module list, launcher and SBOM, and fails the build on any shortfall:
   a project that hasn't locked its runtime options, passes a forbidden JVM option or ships a
   forbidden library gets no archive.
3. **Checked again at install.** `appInstall`, from the app's build or the `app-deploy` plugin,
   refuses a release that breaks the policy, whoever built it, when the installing machine sets
   `appDeploy.securityPolicy` or `appDeploy.securityPolicyArtifact`.

Each runtime records the policy's id, version and SHA-256 in
`runtime/conf/security/java-app-customisations.txt`.

What it cannot do is stop the application's own code: code can call `Security.setProperty`, bring
its own TLS provider or trust manager, or open sockets. `forbiddenLibraries` closes the commonest of
those doors; the rest belongs at the network boundary. See the article's section on corporate
control.

## Runtime options

`runtimeOptions("locked")` (or `-PjavaApp.runtimeOptions=locked`) makes the launcher refuse to start
while anything would add JVM options the build did not fix: `APP_JAVA_OPTS`, `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, or a `jvm.options` file in the configuration directory. The
default, `open`, adds `$APP_CONF_DIR/jvm.options` (a JVM argument file) and then `$APP_JAVA_OPTS`
after the build's `jvmArgs`.

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

To download from a mirror instead, see `javaApp.jdk.url` under [Configuration](reference.md#configuration).

### JDKs already on disk

To use JDKs you already have instead of downloading them, point at a shared directory of archives,
laid out like the [binary repository's runtimes repository](binary-repository.md#repositories-to-create):

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
