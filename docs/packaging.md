# Packaging and deploying

Every build makes two archives per target, the app and its runtime. Linux targets can also be
packaged as RPMs, and any Linux target can become a container image. Which conventions each format follows, and why, is in
[Packaging standards](packaging-standards.md).

| Format | For | How |
|---|---|---|
| `.tar.gz` | Linux and macOS targets: the app, and its runtime | Always built |
| `.zip` | Windows targets: Explorer opens it | Always built |
| `.rpm` | Red Hat family servers managed with dnf | `rpm { enabled = true }` |
| Container image | Kubernetes, ECS, any OCI runtime | `appDockerContext`, then `docker build` |

## Archives

Each target gets the format its own OS opens without a separate tool: `zip` for Windows, `tar.gz`
for Linux and macOS. Whatever the format, each target has two archives, both keeping Unix
permissions (every file readable by everyone, so an account that doesn't own the files can still
run the app):

| Archive | Holds | Published as |
|---|---|---|
| `<app>-<version>-<target>.tar.gz` | One directory, `<app>-<version>-<target>/`: the launchers, libraries, metadata and SBOM | `<group>:<app>:<version>` |
| `<app>-runtime-<version>-<target>.tar.gz` | One directory, `runtime/`: the linked JDK, or Node.js | `<group>:<app>-runtime:<version>` |

The runtime archive names neither the app nor its version, and its times, order and modes are
fixed, so every release whose runtime is unchanged publishes the same bytes. A repository that stores
by checksum, as Artifactory does, keeps them once; see
[Image layers and caching](caching.md#republishing-the-runtime). The app is installed by unpacking
both, the runtime inside the app's directory:

```bash
tar -xzf orders-service-1.4.2-linux-x64.tar.gz
tar -xzf orders-service-runtime-1.4.2-linux-x64.tar.gz -C orders-service-1.4.2-linux-x64
```

The smoke test starts the app installed exactly that way, and a consuming build that resolves the
app by Gradle Module Metadata (`app-smoke-test`, for one) gets the runtime archive for the same
machine with it.

A Mac target's archive is the macOS deliverable. A signed, notarized `.pkg` installer can be made
from it on a Mac; see [Packaging standards](packaging-standards.md#the-macos-archive).

## RPM

```kotlin
description = "Order management service"   // the RPM's summary

javaApp {
    targets("linux-x64", "linux-aarch64")
    rpm { enabled = true }
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

and creates the `orders-service` system account (`user` names another). The package is named
after the app, its licence is the app's `license`, and its URL is the `origin` remote. On the server:

```bash
sudo dnf install orders-service            # from your YUM repository, or a path to the .rpm
sudo systemctl enable --now orders-service
sudo dnf upgrade orders-service            # restarts the service; edited config files are kept
sudo dnf history undo last                 # roll back
```

Per-server settings such as `APP_PORT=8080` go in `/etc/orders-service/env`. Unit changes go in a
drop-in (`systemctl edit orders-service`), not in the packaged unit. Set `systemd = false` for hosts
without systemd.

### RPMs are built, not published

`publish` doesn't upload RPMs. An RPM can't be byte-identical from one build to the next (its
payload carries the build's own times), so each release would store its runtime again, which the
[runtime archive](#archives) avoids. Copy the RPMs from `build/distributions/` to your own YUM
repository if you deploy them, and point dnf at it with a `.repo` file in `/etc/yum.repos.d/`. Have
the repository sign its metadata (`repo_gpgcheck=1`), or sign the packages with `rpm --addsign`
before uploading them (`gpgcheck=1`).

The RPM holds the same directory as this target's archives, runtime included. Only the build
machine's archives are smoke-tested.

## Built with Gradle's own distributions

Each target is a [Gradle distribution](https://docs.gradle.org/current/userguide/distribution_plugin.html)
named after it, so it has the distribution plugin's usual tasks:

| Task | Does |
|---|---|
| `install<Target>Dist`, e.g. `installLinuxX64Dist` | Lays the app out in `build/app/<target>/<app>-<version>/` |
| `<target>DistTar` / `<target>DistZip` | The app archive, `build/distributions/<app>-<version>-<target>.tar.gz` (`.zip` for Windows); only the one for the target's OS is enabled |
| `appRuntimeArchive<Target>` | The runtime archive, `build/distributions/<app>-runtime-<version>-<target>.tar.gz` |
| `appArchive` | Every target's archives |
| `installDist` | Gradle's usual task: this machine's app with its runtime, in `build/install/<app>` |

An app archive's single top-level directory is `<app>-<version>-<target>/`, so archives for two
targets unpack side by side. The distribution's contents are the app alone; `install<Target>Dist` adds
`runtime/`.

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
take the archives as they are.

### One runtime per host

A repository stores an unchanged runtime once, but a server still downloads it with every release.
A deployment step can keep each runtime once per host instead, in a directory named by its
SHA-256, and link the app to it. Artifactory's storage API gives the checksum without downloading
the file:

```bash
set -euo pipefail
# APP, VERSION, TARGET, APP_DIR (where the app archive was unpacked), ART (the repository URL) and TOKEN
FILE="$APP-runtime-$VERSION-$TARGET.tar.gz"
PATH_IN_REPO="com/example/$APP-runtime/$VERSION/$FILE"
CACHE="$HOME/runtimes"
SHA=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$ART/api/storage/libs-release-local/$PATH_IN_REPO" \
  | jq -r .checksums.sha256)
if [ ! -d "$CACHE/$SHA" ]; then
  mkdir -p "$CACHE/$SHA.partial"
  curl -fsS -H "Authorization: Bearer $TOKEN" "$ART/libs-release-local/$PATH_IN_REPO" -o "$CACHE/$FILE"
  echo "$SHA  $CACHE/$FILE" | sha256sum -c -
  tar -xzf "$CACHE/$FILE" -C "$CACHE/$SHA.partial" --strip-components=1
  mv "$CACHE/$SHA.partial" "$CACHE/$SHA"
  rm -f "$CACHE/$FILE"
fi
ln -sfn "$CACHE/$SHA" "$APP_DIR/runtime"
```

Unlike the shared installs this replaces, the link can't point at the wrong runtime: the directory is
named by the bytes the release was built and tested with, so a release whose runtime changed gets a
new directory, and a rollback finds its old one still there. The launcher follows the link. The
cache needs no root, and it is optional: without it each deploy unpacks its own runtime and nothing
else changes. On Windows, use `Get-FileHash -Algorithm SHA256`, `Expand-Archive`, and a directory
junction (`New-Item -ItemType Junction`), which needs no administrator rights, for the link. Keep
directories still linked from an installed release when cleaning the cache up.

## As a container image

The project's `Dockerfile`, at its root, is part of the project: `appDockerfile` creates it if it is
missing, and keeps it up to date. The plugin owns one block of it, between
`# >>> embedded-runtime` and `# <<< embedded-runtime`, and rewrites only that block, only when it
changes (a plugin upgrade moving the base image, say). Anything you add after the block stays as
you wrote it:

```dockerfile
# >>> embedded-runtime: written by the io.github.fiftieshousewife java-app / node-app plugin, …
ARG BASE_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:9.8-1793050346@sha256:…
FROM ${BASE_IMAGE}
…
ENTRYPOINT ["/app/bin/orders", "run"]
# <<< embedded-runtime
USER root
RUN microdnf install -y fontconfig && microdnf clean all
USER 1001
```

Commit it. Nothing in it changes from one build to the next: the release, its commit and that
commit's time are `ARG`s (`APP_VERSION`, `GIT_COMMIT`, `CREATED`), which `appDockerContext` fills in
for each build in the copy it writes to `build/docker-context/`, beside every Linux target's
runtime and app archives, unpacked, one per CPU (`amd64`, `arm64`, the names container platforms
use), so the runtime is a layer of its own: `layers/<cpu>/runtime/` and `layers/<cpu>/app/`, each holding the paths it takes in the
image, with fixed times, so each layer is the same in every build and every image of the app
([Image layers and caching](caching.md)). The same files are also under `runtime/<cpu>/` and
`app/<cpu>/`, for Dockerfiles written before. A Dockerfile without the markers is entirely yours
and is used as written; copy the app in it after `ARG TARGETARCH`, which BuildKit, Buildah and
Podman set to the CPU being built (Docker's legacy builder, `DOCKER_BUILDKIT=0`, does not):

```dockerfile
COPY --chown=1001:0 layers/${TARGETARCH}/runtime/ /
COPY --chown=1001:0 layers/${TARGETARCH}/app/ /
```

A Dockerfile that copies all of `app/`, or the app without its runtime, is refused, since its image
would fail when it starts.

Other images of the same app, such as one for OpenShift or one on UBI micro, go beside it as
`Dockerfile.<name>` (`Dockerfile.openshift`, `Dockerfile.micro`). They are yours, and
`appDockerContext` copies each into the context with this build's values filled in, so
`docker build --file build/docker-context/Dockerfile.openshift build/docker-context` builds one.
UBI micro needs more than a different base image, since the Java runtime loads the system zlib;
[On UBI micro](container-testing.md#on-ubi-micro) has a complete `Dockerfile.micro`.

What the plugin's block holds:

- **Base image by digest.** `registry.access.redhat.com/ubi9/ubi-minimal`, which needs only glibc
  and a shell since the runtime comes with the app, pinned to one rebuild's tag (`9.8-<build>`) and
  the digest of its multi-arch index, so every build of a plugin release starts from the same bytes.
  A plugin release moves the pin. To use a registry mirror or another base image, pass the whole
  reference, digest included:
  `--build-arg BASE_IMAGE=mirror.example.com/ubi9/ubi-minimal:9.8-1793050346@sha256:…` (a mirror
  keeps the digest).
- **OCI labels.** `title`, `version`, `revision` (the commit), `created` (the commit's time, so the
  same commit builds the same image), `source` (the `origin` remote with any credentials removed),
  `licenses` (`license`, default `LicenseRef-Proprietary`), and `base.name` (the base
  image actually used, digest included).
- **Health checks.** `HEALTHCHECK` runs `bin/<app> healthcheck ready`. Docker uses it; OCI-format
  builds (Podman's and Buildah's default) drop it and Kubernetes ignores it, so give Kubernetes
  probes of its own (below).
- **Runs as user 1001** in group 0, logs to standard output and reads its configuration from
  `/config`. It writes data and logs under `/var/lib/app/`, which the image creates writable by its
  user and by group 0, so it works with nothing mounted there, and when OpenShift runs it as an
  arbitrary user in group 0. Mount volumes there to keep what it writes.

```kotlin
javaApp {                                    // or nodeApp
    targets("linux-x64", "linux-aarch64")
    license = "Apache-2.0"
}
```

### Every CPU from one context

`buildx` builds every CPU from the same context and pushes one multi-arch image. The Dockerfile's
one `RUN`, which creates the app's data and log directories, runs the target CPU's shell, so a
machine building another CPU's image needs QEMU registered (Docker Desktop has it; on Linux, and on
GitHub's runners, `docker/setup-qemu-action`). Only that step is emulated; the archives are copied:

```bash
./gradlew appDockerContext
docker run --privileged --rm tonistiigi/binfmt --install arm64,amd64   # once per machine, unless Docker Desktop
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

Alternatively run the container with `docker run --init`. The
[Spring Boot sample](../samples/spring-boot-service) builds an image from each of its Dockerfiles
and tests it with [Testcontainers](https://testcontainers.com) in CI: started, probed with both
modes, checked for its labels and stopped with SIGTERM; see
[Testing the container image](container-testing.md).
