---
name: embedded-runtime-ubi-micro
description: Write and test a Dockerfile.micro that runs a java-app plugin's app on Red Hat UBI 9 micro - zlib added as an RPM, UTF-8 locale, a writable HOME, the app's own labels, licence files and a lightweight healthcheck - and check it with Testcontainers. Use when asked for a smaller image than UBI minimal, a distroless-style or UBI micro image, or why the app won't start on ubi-micro.
---

# Run a java-app on UBI micro

UBI micro has no package manager and only `bash`, `coreutils-single`, `glibc`, `libgcc`, `tzdata` and
the base files. The embedded Java runtime needs one thing more: the `java` launcher's `libjli.so`
loads the system `libz.so.1`. Without it the container exits at once with code 127 and
`libz.so.1: cannot open shared object file`. Node.js apps need `libstdc++` too: keep them on UBI minimal.

## 1. Start from the build context

`./gradlew appDockerContext` writes `build/docker-context/`: the project's `Dockerfile`, each
`Dockerfile.<name>` beside it, and every Linux target's runtime and app archives unpacked as two image trees with fixed times,
`layers/<cpu>/runtime/` and `layers/<cpu>/app/` (also, for older Dockerfiles, `runtime/<cpu>/` and `app/<cpu>/`).
Create `Dockerfile.micro` next to `build.gradle.kts`, copied from the generated `Dockerfile`, and
change it as below. Pin both images by tag and digest: the default Dockerfile's `ubi9/ubi-minimal`
pin, and `ubi9/ubi-micro` from Red Hat's catalog (`docs/container-testing.md` in the plugins'
repository shows the query).

## 2. What to change

- **Three layers on micro's own**: everything else the image adds, then the runtime, then the app.
  A `ubi-minimal` stage builds the first in `/out` and the final stage takes it with one
  `COPY --from=<stage> /out/ /`, which keeps owners, modes and links; the final stage runs nothing.
- **zlib as an RPM**, so scanners and Red Hat's certification see it: in that stage, copy micro's
  root (`COPY --from=micro / /mnt/rootfs/`), run `microdnf install --installroot=/mnt/rootfs
  --releasever=9 --config=/etc/dnf/dnf.conf --noplugins --setopt=cachedir=/var/cache/dnf
  --setopt=reposdir=/etc/yum.repos.d --setopt=varsdir=/etc/dnf/vars --setopt=install_weak_deps=0
  --nodocs zlib`, then `cp -a` `usr/lib64/libz.so.1*` and `var/lib/rpm/.` into `/out` (`cp -a`
  keeps the package's `libz.so.1` link, which a plain file would count as modified).
- **`/var/lib/app/data` and `/logs`** made in `/out`, `chown -R 1001:0` and `chmod -R g=u`.
- **`ENV LANG=C.UTF-8`**: otherwise `sun.jnu.encoding` is ASCII and non-ASCII file names throw
  `InvalidPathException`. Not `en_US.UTF-8`, which micro lacks.
- **`ENV HOME=/var/lib/app`**: user 1001 has no passwd entry, so `user.home` would be `/`.
- **Labels**: set `name`, `vendor`, `maintainer`, `version`, `release`, `summary`, `description`,
  `url`, `vcs-ref` and `build-date` to the app's values. Inherited, they name Red Hat's image.
- **`COPY runtime/${TARGETARCH}/legal/ /out/licenses/java-runtime/`** in that stage: regular files,
  not a link.
- **Healthcheck**: give the probe JVM its own options, since each probe is a second JVM in the
  container's limits: `CMD ["env", "APP_JAVA_OPTS=-Xmx32m -Xss512k -XX:TieredStopAtLevel=1
  -XX:+UseSerialGC", "/app/bin/<app>", "healthcheck", "ready"]`.
- Then `COPY --chown=1001:0 layers/${TARGETARCH}/runtime/ /` and `COPY --chown=1001:0
  layers/${TARGETARCH}/app/ /`: those layers are the same in every build and in every image of the
  app. Keep `USER 1001` and the exec-form `ENTRYPOINT`.

## 3. Test it

Build the image from `build/docker-context` with Testcontainers' `ImageFromDockerfile`, passing
`TARGETARCH` (`amd64` or `arm64`) as a build argument. Start it hardened: `withReadonlyRootfs(true)`,
`withTmpFs` for `/tmp` and `/var/lib/app`, `withMemory`, `withCapDrop(Capability.ALL)` and
`no-new-privileges`. Then check:

- `/app/runtime/bin/java -version` runs, and the app answers on its port.
- `java -XshowSettings:properties -version` shows `sun.jnu.encoding = UTF-8` and `user.home = /var/lib/app`;
  `-XshowSettings:system` (a separate run: given twice, only the last prints) shows the memory limit.
- The image's own `HEALTHCHECK` command (from `inspectImageCmd`) exits 0.
- `docker stop` ends it with exit code 143, well inside the grace period, and not `OOMKilled`.
- The labels above are the app's, and `/licenses` holds the licence as a regular file.
- The image's last two layers (`inspectImageCmd(...).getRootFS().getLayers()`) equal another image's
  of the app, and a rebuild's with `withNoCache(true)`.

On an Apple-silicon Mac, run the engine natively (`colima start --arch aarch64 --vm-type vz`) and
include the `linux-aarch64` target: emulated x86_64 can't run UBI 9 (`CPU does not support x86-64-v2`).

## 4. Build for another CPU

The final stage runs nothing, but the zlib stage's `microdnf` runs the target CPU's binaries, so
building arm64 on x64 (or the reverse) fails with `exec format error` unless QEMU is registered:
`docker/setup-qemu-action` on GitHub Actions, `docker run --privileged --rm tonistiigi/binfmt
--install arm64,amd64` on a Linux machine, built in with Docker Desktop. The zlib stage then installs
the target CPU's package, so one `docker buildx build --platform linux/amd64,linux/arm64 --file
Dockerfile.micro build/docker-context` makes both. A test that starts the image needs a machine of
that CPU; an emulated build proves only that it builds.
