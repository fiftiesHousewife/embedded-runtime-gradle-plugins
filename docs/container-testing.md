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

Testcontainers builds with Docker's own builder, which sets `TARGETARCH` only under BuildKit, so the
test passes it as a build argument, the engine's CPU as container platforms name it.

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
has none, makes `/var/lib/app` writable by group 0, and adds the `io.k8s` and `io.openshift` labels
the console shows.

## On UBI micro

The Dockerfile's base image is UBI minimal. UBI micro is smaller: it has no package manager, only
what an app with its own runtime needs to start. Its packages are `bash`, `coreutils-single`,
`glibc`, `libgcc`, `tzdata` and the files a base system needs; it has no `libstdc++`, `zlib` or
`ca-certificates`.

**Java apps run on it.** The embedded Java runtime needs only glibc (`libc`, `libm`, `libdl`,
`libpthread`, `librt`); it carries its own zlib and its own trusted certificates. The launcher
needs a shell and `dirname`, `basename`, `readlink` and `cat`, all in micro.

**Node.js apps don't, as they are.** The official Node.js builds for Linux need `libstdc++`, which
micro leaves out. Stay on UBI minimal for Node.js.

The sample's [`Dockerfile.micro`](../samples/spring-boot-service/Dockerfile.micro) is the default
Dockerfile on a UBI micro base. To move it to a newer rebuild:

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
