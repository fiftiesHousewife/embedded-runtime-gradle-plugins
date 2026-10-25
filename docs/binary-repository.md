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
`~/.gradle/init.d/`, so Gradle can download the settings plugins themselves from it.

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
systemProp.https.proxyUser=build-user          # if the proxy needs a login
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
runtimes/nodejs/node/24.18.1/node-24.18.1-linux-x64.tar.gz
```

[`deploy/curate-runtimes.sh`](../deploy/curate-runtimes.sh) downloads a JDK of any supported
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
