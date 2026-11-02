---
name: embedded-runtime-ubi-micro
description: Run a java-app plugin's app on Red Hat UBI 9 micro with images {} - no Dockerfile to write, zlib added as a locked RPM, UTF-8 locale, a writable HOME, the app's own labels, licence files and a lightweight healthcheck - and check it with Testcontainers. Use when asked for a smaller image than UBI minimal, a distroless-style or UBI micro image, or why the app won't start on ubi-micro.
---

# Run a java-app on UBI micro

UBI micro has no package manager and only `bash`, `coreutils-single`, `glibc`, `libgcc`, `tzdata` and
the base files. The embedded Java runtime needs one thing more: the `java` launcher's `libjli.so`
loads the system `libz.so.1`. Without it the container exits at once with code 127 and
`libz.so.1: cannot open shared object file`. Node.js apps need `libstdc++` too: keep them on UBI minimal.

The plugin takes care of this. Don't write a `microdnf` stage: it reaches Red Hat's repositories during the
image build, and emulates the target CPU.

## 1. Describe the image

```kotlin
javaApp {
    targets("linux-x64", "linux-aarch64")
    images {
        register("ubi-micro") {
            from(ubiMicro)                     // on a corporate network: your mirror's image, by digest, with ubi-micro in its name
        }
    }
}
```

Name nothing else unless the app needs it: zlib, `LANG=C.UTF-8`, `HOME=/var/lib/app`, the app's own labels,
`/licenses`, `USER 1001`, a writable `/var/lib/app`, the port, a `HEALTHCHECK` with small options and the entrypoint
are all there. `install("<rpm>")`, `env(…)`, `label(…)`, `expose(…)`, `user(…)` add to them; a package's own dependencies
must be in the base or installed too, and one with install scripts is refused. `runtimeOnly()` makes an image of the
runtime alone.

## 2. Lock and build

```bash
./gradlew appImagePackagesLock    # pins each CPU's RPMs and checksums in rpms.lock.yaml: commit it
./gradlew appDockerContext        # fetches them from the binary repository, writes build/docker-context/
docker build --file build/docker-context/Dockerfile.ubi-micro build/docker-context
```

Run the lock again whenever the packages or the base change. RPMs come from the binary repository's
`binaryRepo.repo.ubi` remote (default `ubi-9`), and the image build reaches no repository. On a corporate network, also pass
`--build-arg BUILD_IMAGE=<your mirror's ubi-minimal, by digest>`. The build runs nothing of the target CPU's, so
`docker buildx build --platform linux/amd64,linux/arm64` needs no QEMU. A test that starts the image still needs a machine of
that CPU; a build proves only that it builds.

## 3. Test it

Build the image from `build/docker-context` with Testcontainers' `ImageFromDockerfile`, passing
`TARGETARCH` (`amd64` or `arm64`) as a build argument. Start it hardened: `withReadonlyRootfs(true)`,
`withTmpFs` for `/tmp` and `/var/lib/app`, `withMemory`, `withCapDrop(Capability.ALL)` and
`no-new-privileges`. Then check, the way a platform would rather than the way that is convenient:

- `/app/runtime/bin/java -version` runs, and the app answers on its port.
- `java -XshowSettings:properties -version` shows `sun.jnu.encoding = UTF-8` and `user.home = /var/lib/app`;
  `-XshowSettings:system` (a separate run: given twice, only the last prints) shows the memory limit.
- The image's own `HEALTHCHECK` command (from `inspectImageCmd`) exits 0, and its `Config.User` is `1001`.
- `docker stop` ends it with exit code 143, well inside the grace period, and not `OOMKilled`.
- The labels name the app, not Red Hat, and `find /licenses -type f -size +0` finds the licence, as a regular file.
- zlib is in the RPM database, and there is no package manager.
- The last three layers (`inspectImageCmd(...).getRootFS().getLayers()`), runtime, libraries and app, equal a rebuild's with
  `withNoCache(true)`.

On an Apple-silicon Mac, run the engine natively (`colima start --arch aarch64 --vm-type vz`) and
include the `linux-aarch64` target: emulated x86_64 can't run UBI 9 (`CPU does not support x86-64-v2`).

## 4. Before production

On Kubernetes use `httpGet` probes: an exec probe is a second JVM inside the pod's limit, with the server's options. Mount
`/tmp`, `/var/lib/app/data` and `/var/lib/app/logs`, the last for heap dumps. `docs/container-testing.md`, in the plugins'
repository, lists what else goes wrong and a cheap test for each; `docs/capacity.md` covers sizing the pod.

## A Dockerfile of your own

If the image needs something `images {}` can't describe, write `Dockerfile.<name>` beside `build.gradle.kts` and have
`image { packages("zlib") }` lock its RPMs. The sample's `samples/spring-boot-service/Dockerfile.micro` is the same image by
hand: a stage on the build's own CPU installs the context's `packages/<cpu>/` with `rpm` into a copy of micro's root, and the
final stage copies the result, then `COPY --chown=1001:0 layers/${TARGETARCH}/runtime/ /` and
`layers/${TARGETARCH}/app/ /` (or its two halves, `libs/` and `code/`, as `images {}` does), whose trees give the same layers in
every build.
