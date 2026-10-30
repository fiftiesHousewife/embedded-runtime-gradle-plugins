# Image layers and caching

A container registry stores an image as layers, each named by the digest of its contents, and a
node pulls only the layers it doesn't already hold. Artifactory stores every published file the same
way, once per checksum. So the more of a release that is byte-identical to something already
stored, the less every release costs. This page is about what the plugins make identical, and how
to choose settings that keep it that way.

## What an image is made of

The Dockerfile the plugins write has three layers that matter:

| Layer | Contents | Changes when |
|---|---|---|
| Base image | `ubi9/ubi-minimal`, pinned to one rebuild's tag and digest | A plugin release moves the pin |
| `COPY layers/<cpu>/runtime/ /` | The runtime: the linked JDK or Node.js, 30 to 70 MB | The JDK or Node.js release, or the app's modules, change |
| `COPY layers/<cpu>/app/ /` | The launcher, the app and its libraries (`lib/`) | Anything in the app changes |

The base layer is shared by every app built with the same plugin release, on every node. A release
that changes only the app pushes and pulls only the app layer. The runtime layer is shared too, by
every app on the same runtime and [module set](#shared-module-sets).

## What is reproducible

- **Archives** are byte-identical when rebuilt from the same commit: fixed timestamps and file order.
  An unchanged release therefore rebuilds to the same image layers.
- **Image layers** of the runtime and the app are byte-identical whichever build, machine or
  Dockerfile makes them: every file in `build/docker-context/` has the same fixed time, and each tree
  in `layers/<cpu>/` holds the paths it takes in the image (`app/runtime/…`), so `COPY … /` creates
  no directory with the build's own time. A Dockerfile copying `runtime/<cpu>/` to `/app/runtime/`
  instead still builds, but its layer differs every build, since `COPY` makes `/app` and
  `/app/runtime` then.
- **jlink runtimes** are byte-identical for the same JDK, target and module set. Linking the same
  modules twice gives the same `lib/modules` file, so two apps that link the same set ship the same
  runtime.
- **Runtime archives** are byte-identical across builds and across releases of the app, as long as
  the runtime's inputs are unchanged; see [below](#republishing-the-runtime).
- **RPMs** are not byte-identical (see [Packaging standards](packaging-standards.md)), so they are
  not published; they don't affect images.

## Republishing the runtime

Each release publishes its runtime archive again ([Publishing](publishing.md#publishing-the-same-runtime-again)),
which costs nothing only while the bytes are the same. Nothing fails when they stop being so; storage
just grows. These are what could make them differ, and what the plugins do about each:

| Could differ | What the plugins do |
|---|---|
| The JDK | One exact version, the pin, for every build. Gradle resolves it as a dependency, so `gradle/verification-metadata.xml` can pin its SHA-256 too, which catches a vendor re-releasing a version |
| The app's name or version | Neither is in the runtime archive: its one directory is `runtime/`, and the version is only in the file's name |
| Times and order | Every entry has the same fixed time, in a fixed order |
| Modes | 0755 for the runtime's executables (`bin/`, `jspawnhelper`, `jexec`), 0644 for every other file and 0755 for directories: by path alone, never from the build machine's file system, which on Windows has no modes and elsewhere depends on the umask |
| jlink's own output | Reproducible for the same JDK, modules and options since the fixes for [JDK-8241602](https://bugs.openjdk.org/browse/JDK-8241602) and [JDK-8252730](https://bugs.openjdk.org/browse/JDK-8252730), which covered images built on different machines |
| Debug symbols | `--strip-java-debug-attributes`, not `--strip-debug`: on Linux that also runs the build machine's `objcopy` over the native libraries, and its output depends on that machine's binutils |
| The module list | A [shared set](#shared-module-sets), or the app's own modules sorted |
| Class data sharing | No `--generate-cds-archive`, whose output has not been deterministic |
| Trusted certificates | Written as the JDK's own `cacerts` is, with no password, MAC or encryption: those add random salts, and Java 17's JKS format dates every entry. The same certificates give the same file whichever JDK runs Gradle; a Java 17 runtime's `cacerts` becomes PKCS12, which Java 17 reads |
| The build machine | Linux and macOS agents give the same bytes for every target, Windows targets included. A Windows agent doesn't: its jlink writes `release` with CRLF line endings, writes duplicate legal notices as `Please see ..\java.base\LICENSE` pointers where the others copy the text, and generates some `java.lang.invoke` classes in `lib/modules` differently. Publish runtimes from Linux or macOS agents |
| Compression | gzip by the JVM that runs Gradle. Publish from agents that run Gradle on the same JDK, with Gradle's [daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria), say |

The plugins' own tests rebuild a runtime archive from scratch, and with a new app version, and
compare the checksums. CI does the same for every target on Linux, macOS and Windows agents, then
checks that the Linux and macOS agents built the same bytes for each target, and reports how the
Windows agent's differ.

### What Gradle caches

- **`jlink<Target>` and `customiseRuntime<Target>` are cacheable**, keyed by the pin, the build
  machine and the target, the modules, the jlink options, the certificates, the policy and the
  overlay: not the app's code or version. A change to the app doesn't link the runtime again, and
  a build cache hit restores the same bytes a link would have made.
- **The JDKs themselves aren't hashed** (their pin is the input), so a JDK replaced on disk under
  the same version isn't noticed; dependency verification is what catches that.
- **The build machine is part of the key,** so a runtime linked on a Mac is never reused by a Linux
  agent from a shared build cache, though the link gives the same bytes on both. A Windows agent's
  link really does differ.
- **`appRuntimeArchive<Target>` runs again for a new version,** since the version is in its file's
  name, and writes the same bytes. Archive tasks are never cached; this one only copies.
- **Gradle's dependency cache is keyed by coordinates,** not content, so a build that resolves each
  release, `app-smoke-test` say, downloads the same runtime again under each version. A server can
  keep one copy per checksum instead: see [One runtime per host](packaging.md#one-runtime-per-host).

## Shared module sets

By default an app's runtime is the smallest of a few fixed module sets that holds every module it
needs, rather than exactly those modules, so apps on the same set, JDK and target ship the same
runtime. The sets, their sizes and how to change them are in
[Java: modules, hardening and JDKs](java.md#shared-module-sets). Some heuristics for choosing them:

- **Few, wide sets share more.** Each extra set splits the apps further. JDK 21 has 69 modules and
  about 86 trillion valid combinations, but apps cluster by framework: plain services need two or
  three modules, Spring Boot about eighteen. Sets a few MB apart save each app little and cost every
  node another runtime to hold; four to six sets is a sensible range.
- **The JVM is a fixed cost.** About 20 MB of every runtime is the JVM itself, whatever the modules,
  so the most a smaller set saves is the difference in `lib/modules`: about 11 MB for `java.base`
  and the HTTP server, about 50 MB with every module.
- **Patches reset everything.** A new JDK patch, quarterly, changes every runtime whatever its set.
  Sharing pays between patches, and across apps on the same patch.
- **Choose sets from your own apps.** Every runtime archive records its modules on the `MODULES=`
  line of `runtime/release`. Counting the distinct lines across the runtime archives you publish
  shows which sets would cover most of them:

  ```bash
  for archive in *-runtime-*.tar.gz; do
    tar -xzOf "$archive" runtime/release | grep '^MODULES='
  done | sort | uniq -c | sort -rn
  ```

- **Keep debugging modules out of shared sets.** A set holding a module in
  `security.forbiddenModules` is never chosen, so an app that needs one gets exactly its own
  modules, and the exception stays visible.

## Checking what you ship

- **Which set an app got:** the `MODULES=` line of `runtime/release` in its runtime archive.
- **Whether two images share a runtime:** compare the second-to-last digest each one lists in
  `docker image inspect --format '{{json .RootFS.Layers}}' <image>`, the runtime's layer; the last
  is the app's.
- **Whether a rebuild is identical:** build the same commit twice and compare the archives'
  checksums.
- **Whether two releases share a runtime:** compare the SHA-256 of their runtime archives, which
  Artifactory's storage API gives (`/api/storage/<repo>/<path>`, `checksums.sha256`) without a
  download.
