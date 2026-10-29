# Testing the container image

`appDockerContext` gives you a build context: the project's `Dockerfile`, each `Dockerfile.<name>`
beside it, and every Linux archive, its runtime under `runtime/<cpu>/` and the rest under
`app/<cpu>/`. The plugins construct the Dockerfiles and the context; building images and running
them is left to your build or CI, since how and where images are built (Docker, Buildah, a CI
service) differs from one organisation to the next. The
[Spring Boot sample](../samples/spring-boot-service) shows a complete setup with
[Testcontainers](https://java.testcontainers.org), which this page walks through.

## With Testcontainers

The sample has a test suite of its own, `dockerTest`, which `./gradlew dockerTest` runs; `build` and
`check` don't, so they never need a container engine:

```kotlin
testing {
    suites {
        register<JvmTestSuite>("dockerTest") {
            useJUnitJupiter()
            dependencies { implementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5") }
            targets.all {
                testTask.configure {
                    val context = tasks.named<Sync>("appDockerContext").map { it.destinationDir }
                    inputs.dir(context)
                    systemProperty("context", context.get().path)
                }
            }
        }
    }
}
```

The sample's [`ContainerTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/ContainerTest.java)
builds an image from each Dockerfile in the context with Testcontainers' `ImageFromDockerfile`, and
checks what a deployment relies on:

- **It starts and listens.** Exposing the app's port makes Testcontainers wait until it answers.
- **Both probes pass.** It runs the launcher's `healthcheck live` and `healthcheck ready` inside the
  container, as a Kubernetes exec probe would.
- **It is labelled.** The OCI labels `title`, `version`, `revision`, `created`, `licenses` and
  `base.name` are set.
- **It stops on SIGTERM.** Stopped with a grace period, it exits before being killed.
- **On OpenShift's terms.** `Dockerfile.openshift`'s image is started as an arbitrary user in group 0.

A second test, [`OpenShiftTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/OpenShiftTest.java),
starts `Dockerfile.openshift`'s image as OpenShift's `restricted-v2` policy does: a random user id
with no entry in `/etc/passwd`, group 0, every capability dropped and no privilege escalation. It
checks that the app answers over HTTP and logs to stdout, that this user can read and enter
everything under `/app` but write nothing there, and can write the data, log and home directories,
that the runtime is Oracle's Java 25 on Red Hat Enterprise Linux 9, that `/licenses` holds Oracle's
terms as a regular file, that the image has no `USER`, and that its labels name the app, not UBI.
A third, `MicroTest`, is described [below](#on-ubi-micro).

Testcontainers builds with Docker's own builder, which sets `TARGETARCH` only under BuildKit, so the
test passes it as a build argument, the engine's CPU as container platforms name it. The tests
therefore start the image of the CPU they run on: the plugins' own CI starts the x64 images and only
builds the arm64 ones, under QEMU, since a `RUN` step executes the target CPU's binaries.

### Running it locally

Testcontainers doesn't start a container engine: it connects to one that is running. On a Mac with
[Colima](https://github.com/abiosoft/colima) installed and no `DOCKER_HOST` set, the sample's
`dockerTest` first starts a Colima profile of its own, `docker-test`, unless it is running, natively
for the Mac's CPU (`--arch aarch64`, `--vm-type vz`), and points Testcontainers at it. Nothing else
in the build starts it. Elsewhere, or with `DOCKER_HOST` set, it uses whatever engine `docker` is set
up for, such as Docker Desktop. `colima stop docker-test` stops the VM.

**`Fatal glibc error: CPU does not support x86-64-v2`**, with the container exiting with code
127 at once, means the engine is an emulated x86_64 machine whose virtual CPU predates the
x86-64-v2 instruction set, which UBI 9's glibc requires. It happens on an Apple-silicon Mac whose
Colima VM was created with `--arch x86_64`, and fails every UBI 9 image before the app starts.
Create the engine natively: `colima start --arch aarch64 --vm-type vz`. The image is then built from the `linux-aarch64`
archive, so include that target (`targets(…, "linux-aarch64")`).

## On OpenShift

OpenShift's restricted policy runs an image as an arbitrary user in group 0 and ignores the image's
`USER`. The sample's [`Dockerfile.openshift`](../samples/spring-boot-service/Dockerfile.openshift)
has none, makes `/var/lib/app` writable by group 0 and the user's home, and adds the `io.k8s` and
`io.openshift` labels the console shows. It also replaces the labels UBI sets (`name`, `vendor`,
`version`, `release`, `summary`, `description`, `maintainer`, `url`, `vcs-ref`, `build-date`), which
would otherwise describe Red Hat's base image, and copies the runtime's licence terms (its
`legal/` directory) into `/licenses` as regular files, since the certification check skips links.
Red Hat's certification checks both. `--build-arg RELEASE=<n>` numbers rebuilds of
one version.

## On UBI micro

The Dockerfile's base image is UBI minimal. UBI micro is smaller: it has no package manager, only
what an app with its own runtime needs to start. Its packages are `bash`, `coreutils-single`,
`glibc`, `libgcc`, `tzdata` and the files a base system needs; it has no `libstdc++`, `zlib` or
`ca-certificates`.

**Java apps run on it, with zlib added.** The JVM itself needs only glibc (`libc`, `libm`, `libdl`,
`libpthread`, `librt`) and the runtime carries its own trusted certificates, but the `java`
launcher's `libjli.so` and the runtime's `libzip.so` load the system `libz.so.1`. Without it, `java`
stops at once with `libz.so.1: cannot open shared object file` (exit code 127). The sample's
`Dockerfile.micro` installs the `zlib` RPM into a copy of micro's root with UBI minimal's `microdnf`
and copies in the library and micro's updated RPM database, so the package is recorded as installed
and micro's own layers stay as they are. The launcher needs a shell and `dirname`, `basename`,
`readlink` and `cat`, all in micro.

**Node.js apps don't, as they are.** The official Node.js builds for Linux need `libstdc++`, which
micro leaves out. Stay on UBI minimal for Node.js.

The sample's [`Dockerfile.micro`](../samples/spring-boot-service/Dockerfile.micro) (117 MB, against
195 MB on minimal) also does what a Java image on micro needs beyond zlib:

| In the image | Because |
|---|---|
| `LANG=C.UTF-8` | Without a locale, Java names files in ASCII alone (`sun.jnu.encoding`), and a name such as `résumé` fails with `InvalidPathException`. Micro carries `C.UTF-8` (`glibc-minimal-langpack`); `en_US.UTF-8` would silently fall back to POSIX |
| `HOME=/var/lib/app` | User 1001 has no `/etc/passwd` entry, so `HOME`, and Java's `user.home`, would be `/`, which it can't write |
| The app's own `name`, `vendor`, `maintainer`, `version`, `release`, `summary`, `description`, `url`, `vcs-ref`, `build-date` | Otherwise UBI's labels describe Red Hat's image, and Red Hat's certification rejects Red Hat's name in an image that isn't theirs |
| `/licenses/java-runtime/`, regular files | The terms the runtime is shipped under, where the certification looks |
| `USER 1001`, `/var/lib/app` owned by 1001 and group 0 | Not root; writable by OpenShift's arbitrary user too |
| A `HEALTHCHECK` command with its own `APP_JAVA_OPTS` | Each probe starts a second JVM inside the container's limits; small options keep it quick and light, and the server's own options (an agent, a debug port) don't break every probe |

The sample's [`MicroTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/MicroTest.java)
starts the image as a hardened pod: its own user, every capability dropped, no privilege
escalation, a read-only root filesystem with `/tmp` and `/var/lib/app` as empty volumes, and a
512 MiB memory limit. It checks each row above, that the JVM sees the limit, that the image's own
`HEALTHCHECK` command passes, and that SIGTERM ends it with exit code 143 within ten seconds.

Its `BUILD_IMAGE` is the UBI minimal image that installs zlib. To move the base to a newer rebuild:

1. **Find the current rebuild.** Red Hat tags each rebuild `9.<minor>-<build>` and moves the plain
   `9.<minor>` tag along with it. Red Hat's catalog API gives the newest rebuild's tags and the
   digest of its multi-arch index, which holds for every CPU:

   ```bash
   curl -s 'https://catalog.redhat.com/api/containers/v1/repositories/registry/registry.access.redhat.com/repository/ubi9/ubi-micro/images?page_size=1&sort_by=creation_date%5Bdesc%5D&include=data.repositories.tags.name,data.repositories.manifest_list_digest'
   ```

   The answer lists tags such as `9.8` and `9.8-1791895366`, and `manifest_list_digest`.

   Put the tag and digest in `Dockerfile.micro`'s `ARG BASE_IMAGE`, or pass the whole reference as
   `--build-arg BASE_IMAGE=…`. Renovate's own Dockerfile support keeps a project's
   `Dockerfile.<name>` current; see [Renovate presets](renovate.md#the-base-image-preset).
