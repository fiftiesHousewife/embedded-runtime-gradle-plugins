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
`<app>-<version>/`, keeps Unix permissions, is smoke-tested and published the same way, and a
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

With Artifactory configured, `./gradlew publish` also uploads each RPM to the RPM repository
`artifactory.repo.rpm` (default `rpm-local`) using the same credentials, and Artifactory rebuilds the
repository's `repodata/`. Any other repository that accepts an HTTP PUT works too:

```kotlin
rpm { repositoryUrl = uri("https://nexus.example.com/repository/yum-hosted") }
```

Servers then need a `.repo` file pointing dnf at it, such as `/etc/yum.repos.d/apps.repo`:

```ini
[apps]
name=Our apps
baseurl=https://artifactory.example.com/artifactory/rpm-local
# gpgcheck=1 and gpgkey=… once the RPMs or the repository metadata are signed
gpgcheck=0
```

Two caveats. RPMs are not byte-reproducible, unlike the archives. The RPM is built from the same
directory as this target's archive, but only the archive for the build machine is smoke-tested;
publishing checks that as for archives.

## Installing with Gradle

`appInstall` installs this machine's archive into a directory, from the app's own build or from a
small deployment build on a server or deployment agent. It needs no root, and works the same on
Linux, macOS and Windows:

1. unpacks the archive as `releases/<app>-<version>/`;
2. copies each file of `conf.defaults/` into `conf/` unless it is already there;
3. starts the new release once, against that `conf/`, and waits for its health check: a release
   that fails is deleted and never becomes current;
4. points `current` at it and `previous` at the release it replaces, runs your restart command,
   and deletes all but the newest `keep` releases.

`appRollback` points `current` back at `previous` and runs the restart command.

```
/srv/orders/
  releases/orders-service-1.4.2/
  current -> releases/orders-service-1.4.2
  previous -> releases/orders-service-1.4.1
  conf/                  yours: app configuration, jvm.options (Java), env
```

The launcher recognises this layout (an app directory inside `releases/` takes the directory above
as `APP_ROOT`), so configuration, data and logs live outside any one release.

**From the app's build**, on the machine that runs it:

```kotlin
javaApp {                                            // or nodeApp
    install {
        dir = file("/srv/orders")                    // or -PjavaApp.installDir=/srv/orders
        restartCommand("systemctl", "--user", "restart", "orders")
        keep = 3
    }
}
```

**From a deployment build**, which needs only the published coordinates:

```kotlin
// build.gradle.kts of the deployment build
plugins { id("io.github.fiftieshousewife.app-deploy") version "1.0.0" }
repositories { maven("https://artifactory.example.com/artifactory/libs-release-local") }
dependencies { app("com.example:orders-service:1.4.2") }
appDeploy {
    dir = file("/srv/orders")
    restartCommand("systemctl", "--user", "restart", "orders")
}
```

`./gradlew appInstall` resolves the archive built for this machine's OS and CPU and installs it;
`./gradlew appRollback` goes back. The restart command is how the running process changes: point it
at whatever supervises the app (a systemd user unit whose `ExecStart` is
`/srv/orders/current/bin/orders-service run`, a Windows service, a process manager).

Gradle, and so a JVM, must be on that machine. Where there is none, use the [RPM](#rpm).

## As a container image

`./gradlew appDockerContext` writes `build/docker-context/` with a Dockerfile and a Linux archive
under `app/`, ready for `docker build`. It uses your project's own `Dockerfile` (or
`src/deploy/docker/Dockerfile`, or whatever `dockerfile` is set to) if there is one, and otherwise
generates one whose base image needs only glibc and a shell, labelled with the standard OCI
annotations for the app's name, version and commit. It needs a Linux target, and prefers the one
matching your machine's CPU.
