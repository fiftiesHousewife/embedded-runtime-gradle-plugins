# Behind a corporate Artifactory

Set this once per machine or CI agent in `~/.gradle/gradle.properties`, never in a project:

```properties
artifactory.url=https://artifactory.example.com/artifactory
artifactoryUsername=build-user
artifactoryPassword=<identity or reference token>
```

Every download then goes through Artifactory: libraries, Gradle plugins, JDKs, Node.js and npm
packages. On CI, pass the credentials as `ORG_GRADLE_PROJECT_artifactoryUsername` and
`ORG_GRADLE_PROJECT_artifactoryPassword`.

Also copy [`gradle/init.d/embedded-runtime-artifactory.init.gradle.kts`](../gradle/init.d) into
`~/.gradle/init.d/`, so Gradle can download the settings plugins themselves from Artifactory.

## Repositories to create

| Repository | Type | Holds |
|---|---|---|
| `maven-virtual` | Maven virtual | Maven Central, plus your local repositories |
| `gradle-plugins-virtual` | Maven virtual | `https://plugins.gradle.org/m2/` (which also needs `plugins-artifacts.gradle.org`) |
| `npm-virtual` | npm virtual | registry.npmjs.org |
| `runtimes` | Generic local | Approved JDK and Node.js archives |
| `libs-release-local`, `libs-snapshot-local` | Maven local | Your published apps |
| `rpm-local` | RPM local | Your apps' RPMs, if you build them; Artifactory maintains `repodata/` |

JDK and Node.js archives are read from `runtimes` in this layout:

```
runtimes/temurin/jdk/21.0.12.1_1/jdk-21.0.12.1_1-linux-x64.tar.gz
runtimes/nodejs/node/24.18.1/node-24.18.1-linux-x64.tar.gz
```

[`deploy/curate-runtimes.sh`](../deploy/curate-runtimes.sh) downloads a Temurin or Node.js release,
checks it against the vendor's SHA-256 and uploads it there. An existing tree with a different
layout can be used as it is, by giving its pattern:

```properties
javaApp.jdk.artifactoryPattern=jdks/[organisation]/[classifier]/[revision].[ext]
```

A pattern can use `[organisation]` (the distribution, e.g. `temurin`, or `nodejs`), `[module]` and
`[artifact]` (both `jdk` or `node`), `[revision]` (the version, with `+` written as `_`),
`[classifier]` (the target, e.g. `linux-x64`) and `[ext]` (`tar.gz`, or `zip` for a Windows runtime).
