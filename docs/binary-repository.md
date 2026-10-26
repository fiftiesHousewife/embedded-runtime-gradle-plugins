# Behind a corporate binary repository

Artifactory, Nexus or any server with Maven-layout repositories. The examples use Artifactory's
repository names; use your own with the `binaryRepo.repo.*` properties.

Set this once per machine or CI agent in `~/.gradle/gradle.properties`, never in a project:

```properties
binaryRepo.url=https://repo.example.com
binaryRepoUsername=build-user
binaryRepoPassword=<identity or reference token>
```

Every download then goes through the repository: libraries, Gradle plugins, JDKs, Node.js and npm
packages. On CI, pass the credentials as `ORG_GRADLE_PROJECT_binaryRepoUsername` and
`ORG_GRADLE_PROJECT_binaryRepoPassword`.

Also copy [`gradle/init.d/embedded-runtime-binary-repo.init.gradle.kts`](../gradle/init.d) into
`~/.gradle/init.d/`, so Gradle can download the settings plugins themselves from it, and point the
Gradle wrapper at the repository too, so Gradle itself isn't fetched from `services.gradle.org`:

```properties
# gradle/wrapper/gradle-wrapper.properties: a generic remote repository of https://services.gradle.org/distributions
distributionUrl=https\://repo.example.com/gradle-distributions/gradle-9.8.2-bin.zip
```

Credentials are only ever read from Gradle properties, so they stay in `~/.gradle/gradle.properties`
(or the CI's secret store) and never in a project or the configuration cache.

### Behind a proxy

Inside a corporate network the repository is normally reached directly. Where a proxy is needed,
use the standard JVM properties, which Gradle uses for every download and the plugins pass on to npm
and to RPM uploads:

```properties
# ~/.gradle/gradle.properties
systemProp.https.proxyHost=proxy.example.com
systemProp.https.proxyPort=3128
# if the proxy needs a login
systemProp.https.proxyUser=build-user
systemProp.https.proxyPassword=<password>
systemProp.http.nonProxyHosts=localhost|*.example.com
```

The JDK refuses Basic proxy authentication for HTTPS tunnels by default; to allow it, also set
`systemProp.jdk.http.auth.tunneling.disabledSchemes=` (empty).

## Repositories to create

| Repository | Type | Holds |
|---|---|---|
| `maven-virtual` | Maven virtual | Maven Central, plus your local repositories |
| `gradle-plugins-virtual` | Maven virtual | `https://plugins.gradle.org/m2/` (which also needs `plugins-artifacts.gradle.org`) |
| `npm-virtual` | npm virtual | registry.npmjs.org |
| `runtimes` | Generic local | Approved JDK and Node.js archives |
| `libs-release-local`, `libs-snapshot-local` | Maven local | Your published apps |
| `rpm-local` | RPM local | Your apps' RPMs, if you build them; the repository maintains `repodata/` |

JDK and Node.js archives are read from `runtimes` in this layout:

```
runtimes/temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz
runtimes/nodejs/node/24.22.1/node-24.22.1-linux-x64.tar.gz
```

[`scripts/curate-runtimes.sh`](../scripts/curate-runtimes.sh) downloads a JDK of any supported
distribution (`DISTRIBUTION=temurin` by default, and Temurin's separate jmods for Java 24 and
later) or a Node.js release, checks it against the vendor's SHA-256 and uploads it there. Temurin,
Oracle, Microsoft and SapMachine publish that checksum beside the archive; for Corretto, Zulu and
Liberica pass it from the vendor's release page as `SHA256_<target>`, or the archive is not
uploaded. `DRY_RUN=1` shows what would be mirrored. An existing tree with a different
layout can be used as it is, by giving its pattern:

```properties
javaApp.jdk.binaryRepoPattern=jdks/[organisation]/[classifier]/[revision].[ext]
```

A pattern can use `[organisation]` (the distribution, e.g. `temurin`, or `nodejs`), `[module]` and
`[artifact]` (both `jdk` or `node`), `[revision]` (the version, with `+` written as `_`),
`[classifier]` (the target, e.g. `linux-x64`) and `[ext]` (`tar.gz`, or `zip` for a Windows runtime).

When your file names spell the version differently, `javaApp.jdk.revisionFormat` sets how
`[revision]` is written, from these tokens (shown for `21.0.5+9`): `[version]` (`21.0.5+9`),
`[underscored]` (`21.0.5_9`, the default), `[feature]` (`21`), `[interim]` (`0`), `[update]` (`5`),
`[patch]` (`0`) and `[build]` (`9`).

### JDKs packaged as RPMs

A JDK can come as an RPM: Oracle's own (`jdk-21.0.5_linux-x64_bin.rpm`), or your organisation's
repackaging. Give a pattern that ends in `.rpm`, and if the RPMs live in a repository of their own,
name it with `javaApp.jdk.binaryRepo`:

```properties
# default: binaryRepo.repo.runtimes
javaApp.jdk.binaryRepo=yum-local
# yum-local/java/jdk21_5-64_linux.x86_64.rpm
javaApp.jdk.binaryRepoPattern=java/jdk[revision]-64_linux.x86_64.rpm
javaApp.jdk.revisionFormat=[feature]_[update]
```

The plugin downloads the RPM from the repository like any other dependency (cached, with your
credentials, and nothing from the internet), unpacks it without installing it, and finds the JDK
inside wherever the package puts it (the directory holding `release` and `bin/`; Oracle's is
`/usr/lib/jvm/jdk-21.0.5-oracle-x64`). gzip, xz, bzip2 and zstd payloads are read, the last being
RHEL 9's default. The JDK must include `jmods/`, as Oracle's JDK 21 RPM does.

Gradle can't compile with a JDK it would have to unpack from an RPM, so on those machines it
compiles with an installed JDK of the same feature release and vendor: one Gradle finds on its own
(`/usr/lib/jvm`, for one), or one listed in `org.gradle.java.installations.paths`. It never
downloads one instead; without an installed JDK the build stops and says so.

### Example: Oracle JDK, RPMs on Linux, installed JDKs on Windows

```properties
# ~/.gradle/gradle.properties on every build machine
binaryRepo.url=https://repo.example.com
binaryRepoUsername=build-user
binaryRepoPassword=<token>
javaApp.jdk.distribution=oracle
# the exact release you have approved
javaApp.jdk.21=21.0.5

# Linux agents: the JDK RPM from Artifactory
javaApp.jdk.binaryRepo=yum-local
javaApp.jdk.binaryRepoPattern=java/jdk[revision]-64_linux.x86_64.rpm
javaApp.jdk.revisionFormat=[feature]_[update]

# Windows agents: the JDK already installed (forward slashes: in this file a backslash escapes)
javaApp.jdk.21.windows-x64.path=C:/Program Files/Java/jdk-21
org.gradle.java.installations.paths=C:/Program Files/Java/jdk-21
```

On Linux the RPM supplies the embedded runtime and the installed Oracle JDK compiles; on Windows the
installed JDK does both. Each machine builds its own target (`targets("linux-x64")` on Linux,
`targets("windows-x64")` on Windows, or `-PjavaApp.targets=…`), since linking needs a JDK for the
machine that runs `jlink`. Every JDK must be the pinned release: a build that finds 21.0.4 where
21.0.5 is pinned stops and names both.
