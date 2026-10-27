# Behind a corporate binary repository

Artifactory, Nexus or any server with Maven-layout repositories. The examples use Artifactory's
repository names; use your own with the `binaryRepo.repo.*` properties.

There are two ways to point the plugins at it. Pick the first if your build already declares the
repository, as most corporate builds do. Every property on this page can also be set in
`settings.gradle.kts`, in an `embeddedRuntime {}` block ([Reference](reference.md)), which wins over
the property: `embeddedRuntime { binaryRepository { repository = "artifactory" }; jdk { yumRepo = "yum-local" } }`.

## Your build already declares the repository

Name it, and the plugins use its server and its credentials, whatever your developers called them.
Nothing is copied into a new property, and your build's own repositories are left as they are:

```kotlin
// settings.gradle.kts: what your build already has
plugins {
    id("io.github.fiftieshousewife.java-app.settings") version "0.0.4"
}
dependencyResolutionManagement {
    repositories {
        maven {
            name = "artifactory"
            url = uri("https://acme.jfrog.io/artifactory/maven-virtual")
            credentials {
                username = providers.gradleProperty("artifactory_user").get()
                password = providers.gradleProperty("artifactory_password").get()
            }
        }
    }
}
```

Nothing else is needed: the plugins find it. Among the repositories `settings.gradle.kts` declares,
they pass over public ones (Maven Central, the Gradle Plugin Portal, Google, JitPack, Sonatype) and
local `file:` trees, and borrow what is left. Several repositories on one server (a `maven-virtual`
and a `gradle-plugins-virtual`) count as one server, and the first is taken. If the build declares
repositories on several servers, name the one to borrow; `none` borrows nothing:

```properties
# gradle.properties of the project: no secrets, so it can be committed
binaryRepo.repository=artifactory
```

Credentials given either way work: a `credentials { … }` block reading any property or environment
variable, or `credentials(PasswordCredentials::class)`, which reads `artifactoryUsername` and
`artifactoryPassword` by the repository's name. The repository may also be declared in
`pluginManagement`. It must log in with a user name and password (or token): borrowing exists to
reuse that login, so a repository without one stops the build, and a server that needs no login is
set with `binaryRepo.url` instead.

The plugins add only the repositories for the runtime archives, on the same server as the named
one, beside it: `https://acme.jfrog.io/artifactory/runtimes/` with the layout below, or the
repository and layout you set (see [JDKs packaged as RPMs](#jdks-packaged-as-rpms)):

```properties
# → https://acme.jfrog.io/artifactory/yum-local/
javaApp.jdk.binaryRepo=yum-local
javaApp.jdk.binaryRepoPattern=java/jdk[revision]-64_linux.x86_64.rpm
```

The same login is used for compile JDKs, npm (`binaryRepo.repo.npm`, beside it too), publishing
archives (`binaryRepo.repo.releases`/`snapshots`), uploading RPMs (`binaryRepo.repo.rpm`) and
fetching approved certificates. If `binaryRepo.repository` names a repository the build doesn't
declare, the build stops and lists the ones it does.

## No repository declared: one setting per machine

For machines and CI agents whose builds declare no repository, set this once in
`~/.gradle/gradle.properties`, never in a project:

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
`[artifact]` (both `jdk` or `node`), `[revision]` (the version, with `+` written as `_`) and `[ext]`
(`tar.gz`, or `zip` for a Windows runtime), and these for the target, shown for `linux-x64` and
`linux-aarch64`:

| Token | `linux-x64` | `linux-aarch64` | As named by |
|---|---|---|---|
| `[target]`, `[classifier]` | `linux-x64` | `linux-aarch64` | these plugins, Maven classifiers |
| `[os]` | `linux` | `linux` | these plugins (`windows`, `mac`) |
| `[arch]` | `x64` | `aarch64` | these plugins, most JDK vendors |
| `[rpmArch]` | `x86_64` | `aarch64` | RPM, YUM, `uname -m` |
| `[containerArch]` | `amd64` | `arm64` | container platforms, Debian |

So one pattern names every CPU's file: `java/jdk[revision]-64_[os].[rpmArch].rpm` gives
`java/jdk21_5-64_linux.x86_64.rpm` and `java/jdk21_5-64_linux.aarch64.rpm`.

A JDK pattern can also use the version's parts directly, without `revisionFormat`: `[version]`
(`21.0.5+9`), `[underscored]` (`21.0.5_9`), `[feature]` (`21`), `[interim]` (`0`), `[update]` (`5`),
`[patch]` (`0`) and `[build]` (`9`); a Node.js pattern can use `[version]`. So
`java/[feature]/jdk[feature]_[update]-64_[os].[rpmArch].rpm` needs nothing else.

Where your tree spells a platform its own way, define a token, with a value per OS or per target
(a target's own value wins over its OS's):

```properties
# [plat] is l64 on Linux, w64 on Windows, and la64 on 64-bit ARM Linux
javaApp.jdk.token.plat.linux=l64
javaApp.jdk.token.plat.windows=w64
javaApp.jdk.token.plat.linux-aarch64=la64
javaApp.jdk.binaryRepoPattern=java/jdk-[feature].[interim].[update]_[plat]_bin.[ext]
```

or in `settings.gradle.kts`: `embeddedRuntime { jdk { token("plat", mapOf("linux" to "l64", "windows"
to "w64")) } }`. Node.js tokens are `nodeApp.node.token.<name>.<os or target>`. A pattern naming a
token with no value for some target stops the build and lists the tokens there are. These tokens may
sit anywhere in a pattern, across folders too, but not reach across the edge of an optional `(…)`
part.

When your file names spell the version differently, `javaApp.jdk.revisionFormat` sets how
`[revision]` is written, from these tokens (shown for `21.0.5+9`): `[version]` (`21.0.5+9`),
`[underscored]` (`21.0.5_9`, the default), `[feature]` (`21`), `[interim]` (`0`), `[update]` (`5`),
`[patch]` (`0`) and `[build]` (`9`).

### JDKs packaged as RPMs

A JDK can come as an RPM: Oracle's own, or your organisation's repackaging. If the RPMs are in a
YUM repository (an Artifactory or Nexus YUM repository, or any `createrepo` tree, the one your
servers install from with dnf), name the repository and the package; the plugin finds the package
in the repository's own metadata, so file names don't matter:

```properties
# a repository beside the binary repository (a path in it works too: yum-local/rhel/9), or a full URL
javaApp.jdk.yumRepo=yum-local
# the package name dnf shows; [feature] is the Java release. Oracle's is jdk-[feature], the default for oracle
javaApp.jdk.yumPackage=jdk-[feature]
```

For each target it reads `repodata/repomd.xml` and the primary index it names (gzip, xz, bzip2 or
zstd), and takes the package with that name at the pinned version for the target's CPU (`x86_64`,
`aarch64`), at its newest release. The version is the pin without its build (`21.0.5` for
`javaApp.jdk.21=21.0.5`); a repackaging that spells it differently sets `javaApp.jdk.revisionFormat`
(e.g. `[feature].[interim].[update].0.0.[build]`). If the repository has no such package, the build
stops and lists the versions it does have.

The package is downloaded with the binary repository's login and checked against the SHA-256 the
repository's metadata records, then kept once per machine in Gradle's user home
(`caches/embedded-runtime/yum/`), shared by every build. Once in place the task is up to date and
needs no network. A target given a JDK by path (`javaApp.jdk.21.windows-x64.path`) uses that
instead, so Windows machines keep their installed JDK.

RPMs in a plain file tree (a generic repository, a share) are read by file name instead, with a
pattern that ends in `.rpm`:

```properties
# default: binaryRepo.repo.runtimes
javaApp.jdk.binaryRepo=generic-local
# generic-local/java/jdk21_5-64_linux.x86_64.rpm, and …linux.aarch64.rpm for the other CPU
javaApp.jdk.binaryRepoPattern=java/jdk[revision]-64_[os].[rpmArch].rpm
javaApp.jdk.revisionFormat=[feature]_[update]
```

Either way the RPM is unpacked without being installed, on Linux, macOS and Windows alike: each
symbolic link in it is written as a copy of what it points to, since Windows can't create links
without privileges. A link to something outside the package, such as a JDK's `cacerts` pointing at
the system's `/etc/pki/…`, is left out; jlink builds the runtime's own `cacerts` from the JDK's
modules. The JDK is found inside wherever the package puts it (the directory holding `release` and `bin/`; Oracle's is
`/usr/lib/jvm/jdk-21.0.5-oracle-x64`). gzip, xz, bzip2 and zstd payloads are read, the last being
RHEL 9's default. The JDK must include `jmods/`, as Oracle's JDK 21 RPM does.

Gradle can't compile with a JDK it would have to unpack from an RPM, so on those machines it
compiles with an installed JDK of the same feature release and vendor: one Gradle finds on its own
(`/usr/lib/jvm`, for one), or one listed in `org.gradle.java.installations.paths`. It never
downloads one instead; without an installed JDK the build stops and says so.

### Example: Oracle JDK, RPMs on Linux, installed JDKs on Windows

The build already declares its Artifactory repository, named `artifactory`, with the developers'
own credentials, as in [the first section](#your-build-already-declares-the-repository).

```properties
# gradle.properties of the project
binaryRepo.repository=artifactory
javaApp.jdk.distribution=oracle
# the exact release you have approved
javaApp.jdk.21=21.0.5
# Linux: the jdk-21 package from the yum-local repository beside it, as servers install it
javaApp.jdk.yumRepo=yum-local
```

```properties
# ~/.gradle/gradle.properties on Windows machines: the JDK already installed
# (forward slashes: in this file a backslash escapes)
javaApp.jdk.21.windows-x64.path=C:/Program Files/Java/jdk-21
org.gradle.java.installations.paths=C:/Program Files/Java/jdk-21
```

On Linux the RPM supplies the embedded runtime and the installed Oracle JDK compiles; on Windows the
installed JDK does both. Each machine builds its own target (`targets("linux-x64")` on Linux,
`targets("windows-x64")` on Windows, or `-PjavaApp.targets=…`), since linking needs a JDK for the
machine that runs `jlink`. Every JDK must be the pinned release: a build that finds 21.0.4 where
21.0.5 is pinned stops and names both.
