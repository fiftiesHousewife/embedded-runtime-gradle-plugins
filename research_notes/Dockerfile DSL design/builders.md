# Is there an open-source image DSL to adapt?

Checked 2026-11-02, for: Oracle's jlinked Java 25 and a Spring Boot app on UBI micro, on a network reaching only
the binary repository, with users never meeting microdnf. `engines.md` covers Dockerfile parsers and generators.
READMEs and repository metadata were read today; nothing here was run.

The hard requirement is the RPM step: add a package to a digest-pinned micro base, with micro's RPM database updated.
`packages.md` shows Syft and Trivy miss a library that isn't in the database.

| Tool | Licence | What it offers | Why not |
|---|---|---|---|
| [gradle-oci](https://github.com/sgtsilvio/gradle-oci) 0.32.0, 2026-10-27 | Apache 2.0 | **The closest.** Kotlin DSL, no daemon, reproducible layers from copy specs, base images as Gradle dependencies, a Testcontainers integration | No RPM step, so the part users must not see remains ours, and running `rpm` then needs a container anyway. Writes no Dockerfile, which OpenShift builds and existing users take. 0.x, one maintainer, Plugin Portal only (we publish to Central) |
| [Jib](https://github.com/GoogleContainerTools/jib) | Apache 2.0 | `from {}`, layers, an extension framework | No RPM step (`engines.md`); no Dockerfile |
| [apko](https://github.com/chainguard-dev/apko) | Apache 2.0 | The declarative model we want, reproducible | apk packages only. Chainguard's own RHEL RPM support is a preview in its product, not stated for apko |
| [bazeldnf](https://github.com/rmohr/bazeldnf) | Apache 2.0 | Lockfile, RPMs to a layer tar with no dnf: the same design | Bazel rules. Its static Go binary might be callable, unexplored |
| [mkosi](https://github.com/systemd/mkosi) | LGPL-2.1 (not re-read) | Declarative, knows `rhel-ubi`, outputs OCI | Linux with dnf on the host, builds a whole OS tree rather than adding to a pinned micro, reaches the distribution's repositories by default |
| [Chisel](https://github.com/canonical/chisel) | AGPL-3.0 (not re-read) | Slices of packages | Ubuntu only |
| [rpm-lockfile-prototype](https://github.com/konflux-ci/rpm-lockfile-prototype) | not re-read | A real depsolver for the lock format we write | "No stability guarantees", needs Python's DNF bindings. A lock it writes works here, as the format is the same |
| bmuschko, JKube, buildpacks, Dagger, Earthly | see `engines.md` | | No RPM stage, or experimental, or dead |

Not established: how Red Hat's Project Hummingbird builds its images (the searches found only coverage of the product).

Decision: keep `ImageSpec` as a thin facade over Gradle's own copy and a Dockerfile. It is about 300 lines with its
renderer and labels, against the cost of adopting gradle-oci and still owning the RPM step. Worth revisiting if
gradle-oci gains OS packages (its README's diagrams already draw an "os packages" component) or if the Dockerfile
stops being something users need. The lock stays in Konflux's format, so moving to their tooling stays open.
