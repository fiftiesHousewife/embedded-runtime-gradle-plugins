# Packaging and deploying

Every build makes an archive per target. Linux targets can also be packaged as RPMs, and any Linux
archive can become a container image. Which conventions each format follows, and why, is in
[Packaging standards](packaging-standards.md).

| Format | For | How |
|---|---|---|
| `.tar.gz` (default) | Any server, any OS | Always built |
| `.tar` | Pipelines that compress or layer the archive themselves | `archiveFormat("tar")` |
| `.zip` | People unpacking a Windows build in Explorer | `archiveFormat("zip")` |
| `.rpm` | Red Hat family servers managed with dnf | `rpm { enabled = true }` |
| Container image | Kubernetes, ECS, any OCI runtime | `appDockerContext`, then `docker build` |

## Archives

```kotlin
javaApp {                       // or nodeApp
    archiveFormat("zip")        // or -PjavaApp.archiveFormat=zip
}
```

One format applies to every target. Whatever the format, an archive holds one directory
`<app>-<version>-<target>/`, keeps Unix permissions, is smoke-tested and published the same way, and a
consuming build that resolves by Gradle Module Metadata needs no change.

A Mac target's archive is the macOS deliverable. A signed, notarized `.pkg` installer can be made
from it on a Mac; see [Packaging standards](packaging-standards.md#the-macos-archive).

## RPM

```kotlin
javaApp {
    targets("linux-x64", "linux-aarch64")
    rpm {
        enabled = true
        summary = "Order management service"
        vendor = "Example Ltd"
    }
}
```

`./gradlew build` then also makes `build/distributions/orders-service-1.4.2-1.x86_64.rpm` and
`…aarch64.rpm`, on any build machine: the RPM is written in Java, with no `rpmbuild`. It installs

```
/opt/orders-service/                      the app, owned by root
/etc/orders-service/                      conf.defaults/ as %config(noreplace), and an optional env file
/var/lib/orders-service/  /var/log/orders-service/   writable by the service account only
/usr/lib/systemd/system/orders-service.service
```

and creates the `orders-service` system account. On the server:

```bash
sudo dnf install orders-service            # from your YUM repository, or a path to the .rpm
sudo systemctl enable --now orders-service
sudo dnf upgrade orders-service            # restarts the service; edited config files are kept
sudo dnf history undo last                 # roll back
```

Per-server settings such as `APP_PORT=8080` go in `/etc/orders-service/env`. Unit changes go in a
drop-in (`systemctl edit orders-service`), not in the packaged unit. Set `systemd = false` for hosts
without systemd.

### Publishing RPMs

With a binary repository configured, `./gradlew publish` also uploads each RPM to the RPM repository
`binaryRepo.repo.rpm` (default `rpm-local`) using the same credentials, and the repository (Artifactory, Nexus) rebuilds the
repository's `repodata/`. Any other repository that accepts an HTTP PUT works too:

```kotlin
rpm { repositoryUrl = uri("https://nexus.example.com/repository/yum-hosted") }
```

Servers then need a `.repo` file pointing dnf at it, such as `/etc/yum.repos.d/apps.repo`:

```ini
[apps]
name=Our apps
baseurl=https://repo.example.com/rpm-local
# gpgcheck=1 and gpgkey=… once the RPMs or the repository metadata are signed
gpgcheck=0
```

Two caveats. RPMs are not byte-reproducible, unlike the archives. The RPM is built from the same
directory as this target's archive, but only the archive for the build machine is smoke-tested;
publishing checks that as for archives.

## Built with Gradle's own distributions

Each target is a [Gradle distribution](https://docs.gradle.org/current/userguide/distribution_plugin.html)
named after it, so it has the distribution plugin's usual tasks:

| Task | Does |
|---|---|
| `install<Target>Dist`, e.g. `installLinuxX64Dist` | Lays the app out in `build/app/<target>/<app>-<version>/` |
| `<target>DistTar` / `<target>DistZip` | The archive, `build/distributions/<app>-<version>-<target>.tar.gz` (or `.zip`); only the `archiveFormat` one is enabled |
| `appArchive` | Every target's archive |

An archive's single top-level directory is `<app>-<version>-<target>/`, so archives for two targets
unpack side by side.

Java launchers come from Gradle's own `startScripts` task. The plugin sets a script generator that
starts the embedded runtime and adds `run`, `healthcheck` and `version`; `application {
applicationDefaultJvmArgs }` and `javaApp { jvmArgs }` both end up in the script. To write the
launcher yourself, replace the generator the standard way:

```kotlin
tasks.startScripts {
    unixStartScriptGenerator = MyGenerator()       // an org.gradle.jvm.application.scripts.ScriptGenerator
}
```

The smoke test still starts the result, so a launcher that can't run the app fails the build.

Installing on servers is left to the tools made for it: the RPM above, where dnf installs, upgrades
and rolls back, a container image, or the deployment tooling you already run. Every one of them can
take the archive as it is.

## As a container image

`./gradlew appDockerContext` writes `build/docker-context/` with a Dockerfile and a Linux archive
under `app/`, ready for `docker build`. It uses your project's own `Dockerfile` (or
`src/deploy/docker/Dockerfile`, or whatever `dockerfile` is set to) if there is one, and otherwise
generates one whose base image needs only glibc and a shell, labelled with the standard OCI
annotations for the app's name, version and commit. It needs a Linux target, and prefers the one
matching your machine's CPU.
