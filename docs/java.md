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
a shared directory, or a versioned artifact in Artifactory. Anything not modelled here goes in
`src/deploy/runtime/`, which is copied over the runtime as it is (`runtimeOverlay` to change it).
Each runtime records what was changed, with every certificate's SHA-256 fingerprint, in
`runtime/conf/security/java-app-customisations.txt`.

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

To download from a mirror instead, see `javaApp.jdk.url` under [Configuration](reference.md#configuration).

### JDKs already on disk

To use JDKs you already have instead of downloading them, point at a shared directory of archives,
laid out like the [Artifactory runtimes repository](artifactory.md#repositories-to-create):

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
