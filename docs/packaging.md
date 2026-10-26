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
# The repository signs its metadata with its own GPG key (Artifactory: "GPG Signing" on the
# repository; Nexus: a signing key on the yum-hosted repository), and dnf refuses metadata it can't
# verify. The RPMs themselves are not signed by the plugin.
repo_gpgcheck=1
gpgkey=https://repo.example.com/api/gpg/key/public
gpgcheck=0
```

RPM signatures are PGP by design, so this is where an RPM install is checked: the repository's key
vouches for the metadata, which holds every package's checksum. To sign the packages themselves as
well, run `rpm --addsign` in CI before `appPublishRpm` and set `gpgcheck=1`.

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
| `installDist` | Gradle's usual task: this machine's app with its runtime, in `build/install/<app>` |

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

`./gradlew appDockerContext` writes `build/docker-context/` with a Dockerfile and every Linux
archive, one per CPU under `app/amd64/` and `app/arm64/` (the names container platforms use), ready
for `docker build`. It needs at least one Linux target. It uses your project's own `Dockerfile` (or
`src/deploy/docker/Dockerfile`, or whatever `dockerfile` is set to) if there is one; copy the app
with `COPY app/${TARGETARCH}/ /app/` after `ARG TARGETARCH`, which BuildKit, Buildah and Podman set
to the CPU being built (Docker's legacy builder, `DOCKER_BUILDKIT=0`, does not). A Dockerfile that
still copies all of `app/` is refused by `appDockerfile`, since its image would have no `/app/bin`.

Otherwise it generates one:

- **Base image by digest.** `registry.access.redhat.com/ubi9/ubi-minimal`, which needs only glibc
  and a shell since the runtime is in the archive, pinned to the digest of its multi-arch index, so
  every build of a plugin release starts from the same bytes. A plugin release moves the pin. To use
  a registry mirror or another base image, pass the whole reference, digest included:
  `--build-arg BASE_IMAGE=mirror.example.com/ubi9/ubi-minimal:9.8@sha256:…` (a mirror keeps the
  digest).
- **OCI labels.** `title`, `version`, `revision` (the commit), `created` (the commit's time, so the
  same commit builds the same image), `source` (the `origin` remote with any credentials removed, or
  `sourceUrl`), `licenses` (`license`, default `LicenseRef-Proprietary`), and `base.name` (the base
  image actually used, digest included).
- **Health checks.** `HEALTHCHECK` runs `bin/<app> healthcheck ready`. Docker uses it; OCI-format
  builds (Podman's and Buildah's default) drop it and Kubernetes ignores it, so give Kubernetes
  probes of its own (below).
- **Runs as user 1001** in group 0, logs to standard output and reads its configuration from
  `/config`, as OpenShift's restricted policy expects.

```kotlin
javaApp {                                    // or nodeApp
    targets("linux-x64", "linux-aarch64")
    license = "Apache-2.0"
    sourceUrl = "https://github.com/example/orders"   // default: the origin remote
}
```

### Every CPU from one context

Nothing runs while the image is built, so `buildx` builds every CPU from the same context with no
emulation, and pushes one multi-arch image:

```bash
./gradlew appDockerContext
docker buildx create --use                                 # once: a builder that can push multi-arch images
docker buildx build --platform linux/amd64,linux/arm64 \
    --tag registry.example.com/orders:1.4.2 --push build/docker-context
```

### Kubernetes probes

`bin/<app> healthcheck live` asks whether the process is working (if not, Kubernetes restarts it);
`healthcheck ready` asks whether it can take requests now (if not, it gets no traffic). Your app
receives `--healthcheck live` or `--healthcheck ready`; see [What your app needs to do](your-app.md).

```yaml
containers:
  - name: orders
    image: registry.example.com/orders:1.4.2
    ports: [{ containerPort: 8080 }]
    startupProbe:   { exec: { command: [/app/bin/orders, healthcheck, live] }, periodSeconds: 2, failureThreshold: 60 }
    livenessProbe:  { exec: { command: [/app/bin/orders, healthcheck, live] }, periodSeconds: 10, timeoutSeconds: 5 }
    readinessProbe: { exec: { command: [/app/bin/orders, healthcheck, ready] }, periodSeconds: 5, timeoutSeconds: 5 }
```

Each exec probe starts a second runtime inside the container. That is cheap for Node.js; a JVM takes
a moment and some memory, so give Java probes a `timeoutSeconds` of 5 or more, or point an
`httpGet` probe at the app's own health endpoint when it has one.

### Stopping

`docker stop` and Kubernetes send SIGTERM, then SIGKILL when the grace period ends. The launcher
execs the runtime, so the app is PID 1 and gets the signal directly. A JVM runs its shutdown hooks
(Spring Boot shuts down gracefully). Node.js as PID 1 does nothing: Linux gives PID 1 no default
action for SIGTERM, so a Node.js app must handle it, or it is killed only when the grace period
runs out:

```js
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.once(signal, () => server.close(() => process.exit(0)));
}
```

Alternatively run the container with `docker run --init`. The Docker sample in
[`samples/docker-service`](../samples/docker-service) is built in CI and tested with
[Testcontainers](https://testcontainers.com) (a `dockerTest` suite): started, probed with both
modes, checked for its labels and stopped with SIGTERM. Its arm64 image is built on an x64 machine.
The same suite is a starting point for testing your own image.
