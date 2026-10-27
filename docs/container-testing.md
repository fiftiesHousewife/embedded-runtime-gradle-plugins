# Testing the container image

`appDockerContext` gives you a build context: your project's Dockerfile and every Linux archive,
unpacked under `app/<cpu>/`. Building an image from it, and proving the image runs, happens in your
build. The plugins don't register a task for either, since how and where images are built (Docker,
Buildah, a CI service) differs from one organisation to the next. The
[Docker sample](../samples/docker-service) shows a complete setup with
[Testcontainers](https://java.testcontainers.org), which this page walks through.

## With Testcontainers

The sample's build has two parts:

- **An image task**, an `Exec` running `docker build` on the context `appDockerContext` writes.
- **A test suite**, `dockerTest`, that starts the image with Testcontainers and checks it from
  outside, as Docker and Kubernetes see it.

```kotlin
val image = "my-service:$version"

val dockerImage =
    tasks.register<Exec>("dockerImage") {
        val context = tasks.named<Sync>("appDockerContext").map { it.destinationDir }
        inputs.dir(context)
        commandLine("docker", "build", "--tag", image, context.get().path)
    }

testing {
    suites {
        register<JvmTestSuite>("dockerTest") {
            useJUnitJupiter()
            dependencies { implementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5") }
            targets.all {
                testTask.configure {
                    dependsOn(dockerImage)
                    systemProperty("image", image)
                }
            }
        }
    }
}
```

The sample's [`ContainerTest`](../samples/docker-service/src/dockerTest/java/io/github/fiftieshousewife/samples/docker/ContainerTest.java)
checks what a deployment relies on:

- **It starts and listens.** Exposing the app's port makes Testcontainers wait until it answers.
- **Both probes pass.** It runs the launcher's `healthcheck live` and `healthcheck ready` inside the
  container, as a Kubernetes exec probe would.
- **It is labelled.** The OCI labels `title`, `version`, `revision`, `created`, `licenses` and
  `base.name` are set.
- **It stops on SIGTERM.** Stopped with a grace period, it exits before being killed.

### Skipping when there is no Docker

A developer machine or CI runner without a Docker daemon running Linux containers shouldn't fail
the build, but CI's Linux job should. The sample skips the image and test tasks unless
`docker info` reports Linux, or `-PdockerRequired=true` is set, which makes a missing daemon fail
the build. See its [`build.gradle.kts`](../samples/docker-service/build.gradle.kts).

### Running it locally

Testcontainers doesn't start a container engine: it connects to one that is running. The sample's
build does it instead, with a `dockerEngine` task the image tasks depend on. On a Mac with
[Colima](https://github.com/abiosoft/colima) installed and no `DOCKER_HOST` set, it starts a Colima
profile of the build's own, `docker-test`, unless it is running, and points `docker` and
Testcontainers at it (`DOCKER_HOST`, and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` for the socket
inside the VM). So on a Mac, as on a Linux machine with Docker:

```bash
./gradlew check
```

The first run creates the VM, which takes a minute or two; later runs find it running. The profile
is native to the Mac's CPU (`--arch aarch64` on Apple silicon, Apple's virtualisation with
`--vm-type vz`), which matters: see the next section. Elsewhere, with `DOCKER_HOST` set, or with
`-PdockerTest.colima=false`, the build uses whatever engine `docker` is set up for, such as Docker
Desktop. `colima stop docker-test` stops the VM; `colima delete docker-test` removes it.

To copy this into your own build, see `StartColima` and `engineEnvironment` in the sample's
[`build.gradle.kts`](../samples/docker-service/build.gradle.kts).

**`Fatal glibc error: CPU does not support x86-64-v2`**, with the container exiting with code
127 at once, means the engine is an emulated x86_64 machine whose virtual CPU predates the
x86-64-v2 instruction set, which UBI 9's glibc requires. It happens on an Apple-silicon Mac whose
Colima VM was created with `--arch x86_64`, and fails every UBI 9 image before the app starts. The
sample's own profile avoids it; with an engine of your own, create it natively:
`colima start --arch aarch64 --vm-type vz`. The image is then built from the `linux-aarch64`
archive, so include that target (`targets(…, "linux-aarch64")`).

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

The steps:

1. **Find the current rebuild.** Red Hat tags each rebuild `9.<minor>-<build>` and moves the plain
   `9.<minor>` tag along with it. Red Hat's catalog API gives the newest rebuild's tags and the
   digest of its multi-arch index, which holds for every CPU:

   ```bash
   curl -s 'https://catalog.redhat.com/api/containers/v1/repositories/registry/registry.access.redhat.com/repository/ubi9/ubi-micro/images?page_size=1&sort_by=creation_date%5Bdesc%5D&include=data.repositories.tags.name,data.repositories.manifest_list_digest'
   ```

   The answer lists tags such as `9.8` and `9.8-1791895366`, and `manifest_list_digest`.

2. **Build on it.** Pass the whole reference, tag and digest, as the base image:

   ```bash
   docker build \
     --build-arg BASE_IMAGE=registry.access.redhat.com/ubi9/ubi-micro:9.8-1791895366@sha256:5be877a2a280ae36eb9ab80ca4efb28bbf77c57abbba38137cdc37a03b8465e6 \
     --tag my-service:1.0.0-micro build/docker-context
   ```

   The Dockerfile records it in the `org.opencontainers.image.base.name` label.

3. **Test it as you test the default.** The sample does it with a second target of the same suite,
   `dockerTestMicro`, which builds `docker-service:1.0.0-micro` on the reference in its
   `gradle.properties` (`micro.BASE_IMAGE=…`) and runs the same `ContainerTest`.

4. **Keep it current.** The [UBI Renovate preset](renovate.md#the-base-image-preset) finds
   `BASE_IMAGE=…` references in `gradle.properties` and CI files and proposes each new rebuild.
