# Testing the container image

`appDockerContext` gives you a build context: the project's `Dockerfile`, each `Dockerfile.<name>` beside it, and every
Linux target's archives, unpacked as image trees under `layers/<cpu>/`. The plugins construct the Dockerfiles and the
context; building images and running them is left to your build or CI, since how and where images are built (Docker,
Buildah, a CI service) differs from one organisation to the next. The
[Spring Boot sample](../samples/spring-boot-service) shows a complete setup with
[Testcontainers](https://java.testcontainers.org), which this page walks through.

**In short**

- Java: `images { register("ubi-micro") { from(ubiMicro) } }`. Node.js: UBI minimal.
- Start the image as production will: its own user, no capabilities, a read-only root, a memory limit.
- **Assert the way the platform judges**, not the way that is convenient. Read the image's `User`, not `id`. Look for
  regular files, not through a link. Run `java -version`, not a port wait, so the loader's error is what you see.
- Assert what fails silently: encoding, locale data, exit code 143, the licence text.
- Run the engine natively, and remember that CI starting x64 images says nothing about arm64.

## With Testcontainers

The sample has a test suite of its own, `dockerTest`, which `./gradlew dockerTest` runs; `build` and `check` don't, so
they never need a container engine:

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

The sample's tests are small, and each is there because something could go wrong that only a running image shows:

| Test | What it checks |
|---|---|
| [`ContainerTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/ContainerTest.java) | Every `Dockerfile*`: starts and listens, passes both probes as a Kubernetes exec probe would, has its OCI labels, stops on SIGTERM before being killed. Every image and a rebuild share their runtime and app layers |
| [`OpenShiftTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/OpenShiftTest.java) | As OpenShift's `restricted-v2` starts it: a random user id with no `/etc/passwd` entry, group 0, every capability dropped, no privilege escalation. Reads `/app` but can't write there, writes the data, log and home directories, is Oracle's Java 25 on Red Hat Enterprise Linux 9, has `/licenses` as regular files and the app's own labels |
| [`MicroTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/MicroTest.java) | Both micro images, by hand and from `images {}`, as a hardened pod: their own user, no capabilities, a read-only root with `/tmp` and `/var/lib/app` as empty volumes, 512 MiB. Every row of [What is in the image](#what-is-in-the-image), that the JVM sees the limit, the image's own `HEALTHCHECK` passes, and SIGTERM gives exit code 143 within ten seconds |
| [`UbiMicroTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/UbiMicroTest.java) | The `images {}` image: zlib in the RPM database, no package manager, layers that are micro's untouched followed by the files, the runtime, the libraries and the app, and the same digests from a rebuild without the cache |
| [`JreTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/JreTest.java) | An image of the runtime alone |
| [`CapacityTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/CapacityTest.java) | CPU per request and memory against the limit; see [Sizing pods](capacity.md) |

Testcontainers builds with Docker's own builder, which sets `TARGETARCH` only under BuildKit, so the test passes it as a
build argument, the engine's CPU as container platforms name it. The tests therefore start the image of the CPU they run
on: the plugins' own CI starts the x64 images and only builds the arm64 ones, under QEMU.

### Running it locally

Testcontainers doesn't start a container engine: it connects to one that is running. On a Mac with
[Colima](https://github.com/abiosoft/colima) installed and no `DOCKER_HOST` set, the sample's `dockerTest` first starts a
Colima profile of its own, `docker-test`, unless it is running, natively for the Mac's CPU (`--arch aarch64`,
`--vm-type vz`), and points Testcontainers at it. Nothing else in the build starts it. Elsewhere, or with `DOCKER_HOST`
set, it uses whatever engine `docker` is set up for, such as Docker Desktop. `colima stop docker-test` stops the VM.

**`Fatal glibc error: CPU does not support x86-64-v2`**, with the container exiting with code 127 at once, means the
engine is an emulated x86_64 machine whose virtual CPU predates the x86-64-v2 instruction set, which UBI 9's glibc
requires. It happens on an Apple-silicon Mac whose Colima VM was created with `--arch x86_64`, and fails every UBI 9 image
before the app starts. Create the engine natively: `colima start --arch aarch64 --vm-type vz`. The image is then built
from the `linux-aarch64` archive, so include that target (`targets(…, "linux-aarch64")`).

## What goes wrong in containers

What a Spring Boot service on a jlinked runtime met, or would have, ranked by how likely and how costly. "Handled" says
what `images {}` does; the default `Dockerfile` does less, as the [next section](#on-ubi-micro) sets out. Most take one
assertion to pin down. Behaviour of Red Hat's and the scanners' tools comes from reading their source, not from running
them here.

| Gotcha | Symptom | Handled | Cheap test |
|---|---|---|---|
| No `libz.so.1` on micro; [the JDK links the system zlib](https://mail.openjdk.org/pipermail/build-dev/2016-March/016839.html) on Linux | `libz.so.1: cannot open shared object file`, exit 127, before any Java runs | zlib added and recorded, without being asked | `java -version` in a one-shot container |
| An exec probe is a full second JVM with the server's options, in the pod's cgroup | With a debug or JMX port in `APP_JAVA_OPTS`, every probe fails to bind; a slow probe under a small CPU quota restarts a healthy app; a probe can tip the pod over its limit | The image's `HEALTHCHECK` has small options of its own. Kubernetes exec probes don't, so [use `httpGet`](packaging.md#kubernetes-probes) | Probe with a JDWP agent in `APP_JAVA_OPTS`; time 20 probes at half a CPU |
| Heap at 75% of a small limit leaves too little for everything else | Exit 137, `OOMKilled`, no Java error, under load | No: [measure it](capacity.md#align-memory-with-the-heap) | The limit, swap equal to it, concurrent load, assert not `OOMKilled` |
| A read-only root with no writable `/tmp` | Tomcat: `Unable to create tempDir` | No, as it is the pod's: mount `/tmp` | `withReadonlyRootfs(true)` and a tmpfs. Testcontainers' tmpfs is `noexec` by default and an `emptyDir` isn't, so set the options |
| No `USER` | [Preflight's RunAsNonRoot](https://github.com/redhat-openshift-ecosystem/openshift-preflight/blob/main/internal/policy/container/runs_as_nonroot.go) fails; Kubernetes' `runAsNonRoot` refuses it | `USER 1001`, which costs nothing on OpenShift, as it assigns its own | Read `Config.User` |
| `/licenses` holding a link | [Preflight's HasLicense](https://github.com/redhat-openshift-ecosystem/openshift-preflight/blob/main/internal/policy/container/has_license.go) counts regular files only | Regular files | `find /licenses -type f -size +0`, which doesn't follow links |
| UBI's labels inherited | [Preflight rejects](https://github.com/redhat-openshift-ecosystem/openshift-preflight/blob/main/internal/policy/container/trademark_validator.go) Red Hat's name in `vendor`, `maintainer`, `name`, and the image describes UBI | The app's own | Assert the labels |
| No `LANG` | `sun.jnu.encoding` is ASCII, so a file named `résumé` throws `InvalidPathException` | `LANG=C.UTF-8` (`en_US.UTF-8` isn't on micro) | `java -XshowSettings:properties -version` |
| User 1001 has no `/etc/passwd` entry | `user.home` is `/`, which it can't write | `HOME=/var/lib/app` | The same |
| No `jdk.localedata` below the `server` set | German dates and numbers come out in US English, with no error | No: [decide](caching.md#shared-module-sets) | Format a date with `Locale.GERMANY` |
| Missing provider modules | PKCS#11 or an HSM, Kerberos and SAML's XML signatures fail on first use, not at start | No: the `server` set holds them | Exercise them once |
| Debug flags need modules the runtime lacks | Adding JMX or JDWP during an incident stops the JVM starting | By design: `jdk.jdwp.agent` is forbidden, so plan for JFR | `java --list-modules` against a list you expect |
| Non-zero `runAsGroup`, or a volume with no `fsGroup` | `EACCES` on the data and log directories, which rely on group 0 | No | `withUser("123456:5000")` |
| Heap dumps on the writable layer, always `java_pid1.hprof` | Lost on restart, overwritten, or a pod evicted for ephemeral storage | No: mount a volume on `/var/lib/app/logs` | An out-of-memory run: exit 3 and a dump present |
| Graceful drain longer than the stop timeout | [Spring's 30 s per phase](https://docs.spring.io/spring-boot/4.0/reference/web/graceful-shutdown.html) against `docker stop`'s 10 s, and a Kubernetes grace period that also covers a `preStop` | No: raise `terminationGracePeriodSeconds` | A slow request across a stop: 200, then 143 |
| "Not 137" as the stop assertion | A regression exiting 1, or draining for 19 s, passes | | Assert exit `== 143`, and a time |
| arm64 built but never started | An arm64-only failure goes unseen | Each CPU's RPMs are locked apart, and an `images {}` build runs nothing of the target's | The same tests on an arm64 machine; here, a Mac's Colima |
| Scanners miss a linked JDK | [Syft](https://github.com/anchore/syft/blob/main/syft/pkg/cataloger/java/cataloger.go) reads `runtime/release` and calls it OpenJDK; [Trivy](https://trivy.dev/docs/dev/guide/coverage/language/java/) lists no JDK coverage, but reads `*.cdx.json`, so it sees what the SBOM lists | The SBOM names the runtime | Run your scanner, and assert a JDK 25 component |
| No fonts in either base | `UnsatisfiedLinkError … libfreetype.so.6` the first time text is measured | No: only an app rendering text needs them | A headless `getFontMetrics` call |
| No default CDS archive in a linked runtime | A slower start, with no message | No: [build one on the target](performance.md#what-usually-helps) | Measure startup CPU |
| Podman and Buildah's OCI format drops `HEALTHCHECK` | No container health under Podman | Kubernetes ignores it anyway | Assert `Config.Healthcheck` on Docker builds |
| A licence changing under a pin bump | Oracle's [FAQ](https://www.oracle.com/java/technologies/javase/jdk-faqs.html) has later JDK 25 updates leaving the No-Fee Terms in September 2028 | | `MicroTest` asserts `/licenses` holds "No-Fee Terms" |

## On OpenShift

OpenShift's restricted policy runs an image as an arbitrary user in group 0 and ignores the image's `USER`. The sample's
[`Dockerfile.openshift`](../samples/spring-boot-service/Dockerfile.openshift) has none, makes `/var/lib/app` writable by
group 0 and the user's home, and adds the `io.k8s` and `io.openshift` labels the console shows. It also replaces the labels
UBI sets (`name`, `vendor`, `version`, `release`, `summary`, `description`, `maintainer`, `url`, `vcs-ref`, `build-date`),
which would otherwise describe Red Hat's base image, and copies the runtime's licence terms (its `legal/` directory) into
`/licenses` as regular files, since the certification check skips links. `--build-arg RELEASE=<n>` numbers rebuilds of one
version.

**To be certified, keep a `USER`.** Having none is what preflight's RunAsNonRoot fails, and setting one changes nothing on
OpenShift. The sample's file leaves it out, as OpenShift assigns one; `images {}` keeps 1001 by default.

## On UBI micro

The default Dockerfile's base image is UBI minimal. UBI micro is smaller: it has no package manager, only what an app with
its own runtime needs to start. Its packages are `bash`, `coreutils-single`, `glibc`, `libgcc`, `tzdata` and the files a
base system needs; it has no `libstdc++`, `zlib` or `ca-certificates`.

**Java apps run on it, with zlib added.** The JVM itself needs only glibc (`libc`, `libm`, `libdl`, `libpthread`, `librt`)
and the runtime carries its own trusted certificates, but the `java` launcher's `libjli.so` and the runtime's `libzip.so`
load the system `libz.so.1`. Without it, `java` stops at once with `libz.so.1: cannot open shared object file` (exit code
127). The launcher needs a shell and `dirname`, `basename`, `readlink` and `cat`, all in micro.

**Node.js apps don't, as they are.** The official Node.js builds for Linux need `libstdc++`, which micro leaves out. Stay on
UBI minimal for Node.js.

### Without writing a Dockerfile

`images {}` writes the Dockerfile, and takes care of all of the above and the table below. You describe the image in the
Dockerfile's own words and never meet the package tooling. We looked for something open source to adapt first, and nothing
installs RPMs into a digest-pinned micro base with its RPM database intact; the closest, gradle-oci, has no package step.
So this stays a thin facade rather than a language: an image it can't describe is a `Dockerfile.<name>` of your own.

```kotlin
javaApp {
    images {
        register("ubi-micro") {
            from(ubiMicro)                     // default ubiMinimal; or your mirror's image, by digest
            install("libstdc++")               // only what your app needs beyond the runtime; zlib for Java is added for you
            env("TZ", "Europe/London")
            label("summary", "Order service")
            expose(8080)
            user("1001")                       // the default; "" for none, as OpenShift assigns one
        }
    }
}
```

`register("<name>")` gives `Dockerfile.<name>` in the context, and `runtimeOnly()` makes an image of the runtime with no
app. To add or update a package, run `./gradlew appImagePackagesLock` and commit `rpms.lock.yaml`, which pins the file and
its SHA-256 for each CPU, in Konflux's layout. A package's own dependencies must be in the base or installed too. A package
with install scripts is refused, as they can't run for another CPU.

**How it stays hermetic.** The build fetches the locked RPMs from the binary repository's `binaryRepo.repo.ubi` remote and
checks them against the lock; the image build then reaches no repository at all. A stage on the build machine's own CPU
checks Red Hat's signatures and installs them with `rpm` into a copy of the base's root, and the final stage copies the
result and runs nothing, so building another CPU's image needs no emulation and micro's own layers stay untouched.

**On a corporate network, name your mirror.** `ubiMicro` and `ubiMinimal` name `registry.access.redhat.com`. Give your own
by digest, keeping `ubi-micro` in the name so the plugin knows what the base lacks, and pass the build stage's too:

```kotlin
images { register("ubi-micro") { from("artifactory.example.com/docker-remote/ubi9/ubi-micro:9.8-1791895366@sha256:5be877a2…") } }
```

```bash
docker build --build-arg BUILD_IMAGE=artifactory.example.com/docker-remote/ubi9/ubi-minimal:9.8-1793050346@sha256:6f3a7817… \
  --file build/docker-context/Dockerfile.ubi-micro build/docker-context
```

### What is in the image

On the sample, 117 MB on micro against 196 MB on minimal (linux-aarch64, 2 November 2026). The sample's
[`Dockerfile.micro`](../samples/spring-boot-service/Dockerfile.micro) is the same image by hand, kept while it is compared
with what `images {}` writes. Either way, an image on micro needs this beyond zlib. The default `Dockerfile` on minimal has
the user, group 0 and labels of its own, but not `LANG`, `HOME`, the replaced UBI labels or `/licenses`: add them after its
block if you run it in production.

| In the image | Because |
|---|---|
| `LANG=C.UTF-8` | Without a locale, Java names files in ASCII alone (`sun.jnu.encoding`), and a name such as `résumé` fails with `InvalidPathException`. Micro carries `C.UTF-8` (`glibc-minimal-langpack`); `en_US.UTF-8` would silently fall back to POSIX |
| `HOME=/var/lib/app` | User 1001 has no `/etc/passwd` entry, so `HOME`, and Java's `user.home`, would be `/`, which it can't write |
| The app's own `name`, `vendor`, `maintainer`, `version`, `release`, `summary`, `description`, `url`, `vcs-ref`, `build-date` | Otherwise UBI's labels describe Red Hat's image, and Red Hat's certification rejects Red Hat's name in an image that isn't theirs |
| `/licenses/java-runtime/`, regular files | The terms the runtime is shipped under, where the certification looks |
| `USER 1001`, `/var/lib/app` owned by 1001 and group 0 | Not root; writable by OpenShift's arbitrary user too |
| Layers on micro's own: zlib, the RPM database, `/var/lib/app` and `/licenses` together, then the runtime, the libraries and the app | One build stage prepares the first and the final stage copies it in whole, so the final stage runs nothing. The rest come from `layers/<cpu>/`, whose trees carry fixed times, so they are byte-identical in every build: a registry stores the runtime once, and a new release adds only the app's layer |
| A `HEALTHCHECK` command with its own `APP_JAVA_OPTS` | Each probe starts a second JVM inside the container's limits; small options keep it quick and light, and the server's own options (an agent, a debug port) don't break every probe |

### Moving the base

Its `BUILD_IMAGE` is the UBI minimal image that installs the RPMs. With `from(ubiMicro)`, a plugin release moves both. To
move a base you pin yourself to a newer rebuild:

1. **Find the current rebuild.** Red Hat tags each rebuild `9.<minor>-<build>` and moves the plain `9.<minor>` tag along with
   it. Red Hat's catalog API gives the newest rebuild's tags and the digest of its multi-arch index, which holds for every
   CPU:

   ```bash
   curl -s 'https://catalog.redhat.com/api/containers/v1/repositories/registry/registry.access.redhat.com/repository/ubi9/ubi-micro/images?page_size=1&sort_by=creation_date%5Bdesc%5D&include=data.repositories.tags.name,data.repositories.manifest_list_digest'
   ```

   The answer lists tags such as `9.8` and `9.8-1791895366`, and `manifest_list_digest`.

2. **Set it,** in `from(…)` or `Dockerfile.micro`'s `ARG BASE_IMAGE`, or pass the whole reference as
   `--build-arg BASE_IMAGE=…`. Renovate's own Dockerfile support keeps a project's `Dockerfile.<name>` current; see
   [Renovate presets](renovate.md#the-base-image-preset).
3. **Run `appImagePackagesLock` again,** so the locked RPMs match, and commit the lock.
