# Image layers and caching

A container registry stores an image as layers, each named by the digest of its contents, and a
node pulls only the layers it doesn't already hold. So the more of an image that is byte-identical
to something already pulled, the less every release costs. This page is about what the plugins
make identical, and how to choose settings that keep it that way.

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
- **RPMs** are not byte-identical (see [Packaging standards](packaging-standards.md)); they don't
  affect images.

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
- **Choose sets from your own apps.** Every archive records its modules on the `MODULES=` line of
  `runtime/release`. Counting the distinct lines across the archives you publish shows which sets
  would cover most of them:

  ```bash
  for archive in *.tar.gz; do
    release=$(tar -tzf "$archive" | grep '/runtime/release$')
    tar -xzOf "$archive" "$release" | grep '^MODULES='
  done | sort | uniq -c | sort -rn
  ```

- **Keep debugging modules out of shared sets.** A set holding a module in
  `security.forbiddenModules` is never chosen, so an app that needs one gets exactly its own
  modules, and the exception stays visible.

## Checking what you ship

- **Which set an app got:** the `MODULES=` line of `runtime/release` in its archive.
- **Whether two images share a runtime:** compare the digest of their app layers with
  `docker image inspect --format '{{json .RootFS.Layers}}' <image>`. Today that layer holds the app
  too, so it matches only between identical apps.
- **Whether a rebuild is identical:** build the same commit twice and compare the archives'
  checksums.
