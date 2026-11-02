# Extra RPMs in UBI 9 micro images: declaring, locking, installing without emulation

Research date: 2026-11-02. Every claim is tagged **[V-web]** (read from the linked page or source
today), **[V-test]** (reproduced today on Docker 27.4 / BuildKit, colima aarch64 VM, UBI 9.8
images pulled today), or **UNVERIFIED** (not fetched or not tested).

Test environment: host aarch64; `ubi9/ubi` has dnf 4.14.0-34.el9_8 and rpm 4.16.1.3-40.el9;
`ubi9/ubi-minimal` has rpm 4.16.1.3. The VM had amd64 emulation registered, so "no emulation"
was proved by construction (no `RUN` in a target-arch stage) plus a marker `ldconfig` that
records whether a target-arch binary was executed, not by removing binfmt.

---

## 1. Konflux `rpm-lockfile-prototype`, Hermeto and Renovate

Sources: <https://github.com/konflux-ci/rpm-lockfile-prototype>,
<https://raw.githubusercontent.com/hermetoproject/hermeto/main/docs/rpm.md>,
<https://hermetoproject.github.io/hermeto/rpm/>,
<https://konflux-ci.dev/docs/building/prefetching-dependencies/>,
<https://konflux-ci.dev/docs/mintmaker/rpm-lockfile/>.

### Input: `rpms.in.yaml` [V-web]

Keys from the README: `contentOrigin` (`repos` with `repoid`/`baseurl`, `repofiles` by path, URL
or git, `composes`), `packages` (names or `{name, arches: {only|not}}`),
`packagesFromContainerfile` (scans `RUN dnf/yum/microdnf install`), `reinstallPackages`,
`upgradePackages`, `excludePackages`, `assumeProvides`, `matchContextVersions` (pin globs to the
context image's versions), `moduleEnable`, `arches`, `context` (`image`, `containerfile`,
`flatpak`, `bare`, `localSystem`, `rpmOstreeTreefile`), `allowerasing`, `installWeakDeps`,
`zchunk`, `variables`. "There are no stability guarantees."

Example for this plugin's case (assembled from the documented keys; not run):

```yaml
contentOrigin:
  repofiles: [./ubi.repo]        # copied from the ubi9 image's /etc/yum.repos.d/ubi.repo
packages: [zlib]
arches: [x86_64, aarch64]
context:
  image: registry.access.redhat.com/ubi9/ubi-micro:9.8-...   # resolve against micro's rpmdb
installWeakDeps: false
```

### How it depsolves [V-web]

README "Iteration 3/4": the tool pulls the base image, copies its **rpmdb** into a temp dir and
uses that as DNF's installroot, so the result is only the requested packages plus transitive
deps **not already in the image**, at versions consistent with it. Iteration 4 replaced podman
with skopeo (no privileges needed in a container). Iteration 1 notes solving non-native arches
"requires emulation"; iteration 3 "seems to work with any architecture" (depsolving runs no
package code). `--bare` resolves as if nothing is installed. `--arch` repeatable; overrides the
file's `arches`. Needs the system Python's DNF bindings (i.e. run on Fedora/RHEL or in its
container image).

### Output: `rpms.lock.yaml` [V-web, Hermeto docs]

```yaml
lockfileVersion: 1          # must be 1
lockfileVendor: redhat      # only "redhat"
arches:
  - arch: x86_64
    packages:
      - url: https://cdn-ubi.redhat.com/.../Packages/z/zlib-1.2.11-40.el9.x86_64.rpm   # required
        repoid: ubi-9-baseos-rpms        # optional; Hermeto generates hermeto-XXXXXX if absent
        checksum: sha256:baf95ffb...     # optional, "algorithm:digest"
        size: 95708                      # optional, bytes
        name: zlib                       # extra field (written by the prototype, ignored by Hermeto)
        evr: 1.2.11-40.el9               # extra field
        sourcerpm: zlib-1.2.11-40.el9.src.rpm  # extra field
    source: []                           # SRPMs
    module_metadata: []
```

(The url/checksum/size values above are the real ones from today's UBI primary.xml, see §5.)

### Prefetch and install [V-web]

`hermeto fetch-deps` downloads every URL, checks checksum and size, writes an SBOM, and lays out
`deps/rpm/<arch>/<repoid>/`. `inject-files` runs `createrepo_c` per repo dir and writes
`deps/rpm/<arch>/repos.d/hermeto.repo` with `baseurl=file:///...`. The documented Containerfile
is just `RUN dnf -y install httpd-tools`, built with that `repos.d` mounted at
`/etc/yum.repos.d` and `--network none`. So installation is **dnf against a generated local
repo**, not `rpm -i`. Konflux's doc adds: with a custom installroot (`dnf --installroot`) import
the GPG keys into it first. Nothing in either doc addresses cross-arch installs; Konflux builds
each arch on a native or emulated builder (UNVERIFIED for which).

### Renovate [V-web]

- **Upstream Renovate has no rpm/rpm-lockfile manager.** The manager directory on `main`
  (`lib/modules/manager`, listed via the GitHub API today) has none; `lockFileMaintenance`'s
  supported-files table is generated from managers that implement it.
- **Upstream has an `rpm` datasource** (`lib/modules/datasource/rpm`): give `registryUrl` = a
  `.../repodata/` dir; it reads `repomd.xml` → `primary.xml.gz`, streams it, and returns versions
  as `ver-rel` (registryStrategy `merge`, so baseos+appstream can be combined). It returns
  **versions only** — no checksum, URL or arch filter. Usable from a `customManagers` regex.
- **Konflux MintMaker** is "a custom extension on top of Renovate" that regenerates
  `rpms.lock.yaml` with rpm-lockfile-prototype for any directory containing `rpms.in.yaml`.
  Limitations it states: no pinning, all RPM updates in one PR, no single-dependency updates.
  PRs appear as `red-hat-konflux[bot]`, branch `konflux/mintmaker/<branch>/lock-file-maintenance`
  (e.g. <https://github.com/stackrox/fact/pull/425>). Only for repos onboarded to Konflux.
- `postUpgradeTasks` (to rerun a lock generator after a regex bump) "are blocked by default";
  commands must match the global `allowedCommands`; on Mend-hosted apps it depends on plan
  (docs/usage/configuration-options.md).

## 2. Red Hat guidance on micro, and dnf `--forcearch`

### Official micro recipe [V-web]

RHEL 9 "Building, running, and managing containers", §8.2 Using the UBI micro images:

```
microcontainer=$(buildah from registry.access.redhat.com/ubi9/ubi-micro)
micromount=$(buildah mount $microcontainer)
dnf install --installroot $micromount --releasever=/ --setopt install_weak_deps=false \
    --setopt=reposdir=/etc/yum.repos.d/ --nodocs -y httpd
dnf clean all --installroot $micromount
buildah umount $microcontainer
buildah commit $microcontainer ubi-micro-httpd
```

i.e. the **host's** dnf installs into micro's mounted rootfs. No multi-stage Dockerfile and no
arch discussion in Red Hat's doc. The catalog page for ubi9/ubi-micro (latest 9.8-1791895366)
had no usage text when fetched.

The closest published multi-stage recipe is Keycloak's (<https://www.keycloak.org/server/containers>):
`FROM registry.access.redhat.com/ubi9 AS ubi-micro-build`, `dnf install --installroot
/mnt/rootfs <pkgs> --releasever 9 --setopt install_weak_deps=false --nodocs -y`,
`dnf --installroot /mnt/rootfs clean all`, `rpm --root /mnt/rootfs -e --nodeps setup`, then
`COPY --from=ubi-micro-build /mnt/rootfs /`. That installs into an **empty** root (so pulls
glibc, filesystem… again) and removes `setup` to avoid overwriting `/etc/passwd`; the plugin's
"copy micro's rootfs first" approach avoids both.

### dnf `--forcearch` [V-web + V-test]

dnf docs: "Force the use of an architecture. Any architecture can be specified. However, use of
an architecture not supported natively by your CPU will require emulation of some kind."
That caveat is about **running package code**, not dnf itself. Tested in a native aarch64
`ubi9/ubi` stage (`FROM --platform=$BUILDPLATFORM`) with micro **amd64** rootfs copied to
`/mnt/rootfs`:

| Command | Result |
|---|---|
| `dnf --installroot=/mnt/rootfs --forcearch=x86_64 --releasever=9 ... install --downloadonly zlib` | depsolved against micro's rpmdb, downloaded `zlib-1.2.11-40.el9.x86_64.rpm`; nothing executed |
| same, real install | installed; output shows `Running scriptlet: zlib…`; the marker proved glibc's file trigger **ran the target's `/usr/sbin/ldconfig` (x86_64)** — would fail without emulation |
| same with `--setopt=tsflags=nodocs,noscripts,notriggers` | installed; **no target binary executed** |
| `rpm --root R -i --noscripts --notriggers zlib.x86_64.rpm` | refused: "package … is intended for a different architecture" |
| `rpm --root R -i --ignorearch --noscripts --notriggers --excludedocs …` | rc=0, `rpm --root R -q zlib` → `zlib-1.2.11-40.el9.x86_64`, `var/lib/rpm/rpmdb.sqlite` updated, no target binary executed |

What triggers: glibc's spec (CentOS Stream 9, `c9s` glibc.spec, fetched) has
`%transfiletriggerin common -P 2000000 -- /lib /usr/lib /lib64 /usr/lib64` → `/sbin/ldconfig`
(and the matching `%transfiletriggerpostun`). Any package dropping a file in a lib dir fires it.
Skipping it only leaves `/etc/ld.so.cache` without the new library; glibc still finds it because
`/lib64` and `/usr/lib64` are the "system search path" (ld.so `--help`, run in the built image).
For packages **with real scriptlets** (users/groups, alternatives, `update-ca-trust`,
`%post` symlinks), noscripts changes the result — the DSL must refuse or flag them (rpm can
report them: `rpm -qp --scripts --triggers`). zlib has none (verified by the owner).

Other dnf side effects in the installroot [V-test]: `/var/lib/dnf`, `/var/log/dnf*.log`, the
cachedir, `/etc/pki/product*` ("Installed products updated" from the product-id plugin; use
`--noplugins`). Irrelevant if only the package's files and the rpmdb are copied out.

microdnf: no `--forcearch`, can't install a local `.rpm` (owner's tests). Not re-checked.

## 3. How scanners find RPMs

| Scanner | Reads | Source |
|---|---|---|
| Trivy | rpmdb at `{var/lib,usr/lib/sysimage}/rpm/{Packages,Packages.db,rpmdb.sqlite}`, `usr/share/rpm/rpmdb.sqlite`; content sets from `root/buildinfo/content_manifests/*.json` and `usr/share/buildinfo/*.json` (field `content_sets` only) to choose CPEs | `pkg/fanal/analyzer/pkg/rpm/rpm.go`, `.../buildinfo/content_manifest.go` [V-web] |
| Syft (and so Grype) | `**/{var/lib,usr/share,usr/lib/sysimage}/rpm/{Packages,Packages.db,rpmdb.sqlite}`; also `**/*.rpm` files lying in the image | `syft/pkg/rpm.go`, `redhat/cataloger.go` [V-web] |
| Clair / claircore (Quay, Konflux clair-scan, ACS scanner v4 — the latter two UNVERIFIED) | rpmdb; repository→CPE via `root/buildinfo/content_manifests/*.json`, `usr/share/buildinfo/...`; if no manifest or `from_dnf_hint: true`, repoids from dnf history `var/lib/dnf/history.sqlite` (dnf4) or libdnf5's, mapped through Red Hat's `repository-to-cpe.json` | `rhel/rhel.go`, `rhel/repositoryscanner.go`, `internal/dnf/dnf.go` [V-web] |

Note: ubi9-micro 9.8 ships `/root/buildinfo/content-sets.json` (`from_dnf_hint: true`, baseos
content sets for all arches) [V-test], which is **not** under `content_manifests/`, so by the
globs above neither Trivy nor claircore reads it (inference from source; behaviour not run
against a vulnerability DB).

Files without rpmdb entries [V-test]: built the same amd64 micro image twice, once copying
`libz` **and** the updated rpmdb, once `libz` only. Syft 1.55.0: 24 vs 23 rows, `zlib
1.2.11-40.el9 rpm` only in the first. Trivy 0.76.1 (CycloneDX output): 23 vs 22 libraries,
`zlib 1.2.11-40.el9` only in the first. **An unrecorded library is invisible to both.**

Attached SBOMs: Trivy uses one only when asked — `--sbom-sources oci|rekor` (OCI via the
Referrers API); "If an SBOM is found, the vulnerability scan is performed using the SBOM instead
of the container image." It can also find SBOM files inside images. [V-web,
<https://trivy.dev/latest/docs/target/container_image/>]. Grype/Clair consulting attached SBOMs
by default: UNVERIFIED (not found). So the rpmdb has to be right; an SBOM is a supplement.

## 4. BuildKit and buildah features

- `ADD --checksum=sha256:<hex>`: minimum Dockerfile syntax **1.6**, HTTP and Git sources, SHA-256
  only (Dockerfile reference) [V-web]. Worked today with the daemon's built-in frontend and no
  `# syntax` line [V-test].
- `FROM --platform=$BUILDPLATFORM`, and `TARGETPLATFORM/TARGETOS/TARGETARCH/TARGETVARIANT`,
  `BUILDPLATFORM/BUILDOS/BUILDARCH` are automatic in BuildKit (must be redeclared with `ARG`
  inside a stage to use in `RUN`) [V-web; ARG-in-stage behaviour V-test].
- `RUN --mount=type=cache,id=…,sharing=shared|private|locked,target=…` [V-web].
- `SOURCE_DATE_EPOCH`: build arg since Dockerfile 1.5 / BuildKit 0.11; sets image `created` and
  history; buildx ≥ 0.10 passes it from the env; `rewrite-timestamp=true` image-exporter option
  (BuildKit ≥ 0.13) rewrites file timestamps in layers (moby/buildkit `docs/build-repro.md`)
  [V-web].
- buildah: Containerfile `ADD --checksum` for HTTP since **v1.33.0** (2023-11-17 release notes)
  [V-web]; `RUN --mount=type=cache` with `id`, `from`, `mode`, `uid`, `gid`, `ro/rw` (no `sharing`
  listed) and `TARGETPLATFORM`/`BUILDPLATFORM` ("declare them within each FROM section") in
  containers/common `Containerfile.5.md` [V-web]; `buildah build --source-date-epoch` and
  `--rewrite-timestamp` (clamps newer timestamps) in buildah-build(1) [V-web]; `--platform` "RUN
  instructions require emulation" for foreign arches — same constraint as BuildKit. Buildah
  version shipped by OpenShift Builds / `docker` strategy BuildConfigs: UNVERIFIED (check the
  cluster; ADD --checksum needs ≥ 1.33).

## 5. UBI 9 repositories [V-test]

From ubi9 images' `/etc/yum.repos.d/ubi.repo`:

```
https://cdn-ubi.redhat.com/content/public/ubi/dist/ubi9/9/$basearch/baseos/os
https://cdn-ubi.redhat.com/content/public/ubi/dist/ubi9/9/$basearch/appstream/os
https://cdn-ubi.redhat.com/content/public/ubi/dist/ubi9/9/$basearch/codeready-builder/os
(+ /debug and /source/SRPMS variants, disabled)   gpgkey RPM-GPG-KEY-redhat-release
```

`$basearch` ∈ x86_64, aarch64, ppc64le, s390x. `repodata/repomd.xml` is public, no
`repomd.xml.asc` (404): metadata is unsigned, packages are GPG-signed. repomd lists `primary`,
`filelists`, `other` (xml.gz and sqlite.bz2), `group`, `updateinfo`.

Sizes today: baseos x86_64 primary.xml.gz 192 KB (1.6 MB open, 570 packages), aarch64 158 KB
(1.1 MB, 391); appstream x86_64 primary 601 KB (5.5 MB open). Each `<package>` gives
`name/arch/version(epoch,ver,rel)`, `checksum type="sha256"`, `location href="Packages/z/…"`,
`size package=…`, and requires/provides. Today: `zlib-1.2.11-40.el9.x86_64.rpm`
sha256 `baf95ffbf40ee014135f16fe33e343faf7ff1ca06509fd97cd988e6afeabf670`, 95708 bytes;
`.aarch64.rpm` sha256 `2e7f193e67235130c10f5579c2d2ec92e22e4098b6d12fb2855d93b1540c60f7`,
94454 bytes. Parsing that in Java (StAX) is entirely reasonable; a full depsolver is not — but a
closure over `rpm:requires` vs `rpm:provides`/file entries minus what micro already provides is
small for a handful of libraries.

**Retention:** in today's baseos metadata **no package has more than one version** (0 of 570 on
x86_64, 0 of 391 on aarch64): UBI metadata carries only the latest NEVRA. So a pinned NEVRA
disappears from `dnf`/`microdnf` resolution the day an update ships — `microdnf install
zlib-1.2.11-40.el9` will break then. The **files** stay downloadable after they leave the
metadata: HEAD 200 today for `tzdata-2024a-1.el9`, `2025b`, `2025c`, `2026a` and
`glibc-2.34-231.el9_7.2` while the metadata lists only `tzdata-2026e-1.el9_8` and
`glibc-2.34-283.el9_8`; `glibc-2.34-168.el9_6` returned 404. Red Hat's retention policy for UBI
CDN files: UNVERIFIED (none found). Treat old URLs as "usually there for a while", not guaranteed.

## 6. Reproducibility of the rpmdb [V-test]

rpm 4.16 records `INSTALLTIME`; with `SOURCE_DATE_EPOCH=1700000000` set it records that value,
and two installs into two copies of the micro rootfs produced **byte-identical**
`rpmdb.sqlite` (sha256 `56d88205…`), without it they differ. (`-shm`/`-wal` sidecar files are
left, the WAL empty.)

## 7. End-to-end proof [V-test]

```dockerfile
ARG MICRO=registry.access.redhat.com/ubi9/ubi-micro:latest
ARG MINIMAL=registry.access.redhat.com/ubi9/ubi-minimal:latest
FROM ${MICRO} AS micro
FROM --platform=$BUILDPLATFORM ${MINIMAL} AS rpms
ARG TARGETARCH
ADD --checksum=sha256:baf95ffb… https://cdn-ubi.redhat.com/…/x86_64/baseos/os/Packages/z/zlib-1.2.11-40.el9.x86_64.rpm /rpms/amd64/
ADD --checksum=sha256:2e7f193e… https://cdn-ubi.redhat.com/…/aarch64/baseos/os/Packages/z/zlib-1.2.11-40.el9.aarch64.rpm /rpms/arm64/
COPY --from=micro / /mnt/rootfs
RUN rpm --root /mnt/rootfs -i --ignorearch --noscripts --notriggers --excludedocs --nosignature /rpms/${TARGETARCH}/*.rpm
FROM micro
COPY --from=rpms /mnt/rootfs/usr/lib64/libz.so.1 /mnt/rootfs/usr/lib64/libz.so.1.2.11 /usr/lib64/
COPY --from=rpms /mnt/rootfs/var/lib/rpm/ /var/lib/rpm/
```

Built `--platform linux/amd64` on the aarch64 host: the only `RUN` ran natively (`uname -m` →
aarch64), rpmdb lists `zlib-1.2.11-40.el9.x86_64`, Syft and Trivy both report it. Gotcha seen:
`COPY` of a symlink path copies the target, so `libz.so.1` became a second regular file — copy a
staged directory instead (symlinks inside a copied directory are preserved). `--nosignature`
was used for brevity; better to `rpmkeys --root R --import RPM-GPG-KEY-redhat-release` and drop
it, though the sha256 pin already fixes the bytes.

## 8. Recommended design

**Declare.** `micro { packages("zlib") }` (names only, optional `arches`), plus repos defaulting to
UBI baseos+appstream for the major of the micro tag.

**Lock.** A committed per-image lock in the `rpms.lock.yaml` schema (lockfileVersion 1, per-arch
`url`, `checksum`, `size`, `name`, `evr`, `repoid`, `sourcerpm`) — a published format Hermeto
consumes and MintMaker maintains, so Konflux users get updates for free. A Gradle task
(`microLock`, cf. `--write-locks`) writes it: either (a) in Java — fetch `repomd.xml` →
`primary.xml.gz` per arch, resolve the closure of the declared names' requires against the
provides of the micro image (read its rpmdb once, or a list recorded in the lock next to the
micro digest), record URL+sha256+size; or (b) delegate to rpm-lockfile-prototype with
`context.image` = the pinned micro digest. (b) is the real depsolver; (a) avoids Python/containers
and is adequate for a few leaf libraries. Fail the lock when a package has `%pre/%post/%trigger`
scripts (report them with the reason) and when a requirement is neither in micro nor locked.
Lock the micro and ubi-minimal digests in the same file so base-image and RPM changes move
together (the Konflux pain point RHWA-540 is exactly drift between the two).

**Install, no emulation.** Generate: build stage `FROM --platform=$BUILDPLATFORM ubi-minimal@digest`;
one `ADD --checksum=sha256:… <url> /rpms/<targetarch>/` per locked RPM; `COPY --from=micro /
/mnt/rootfs`; `RUN SOURCE_DATE_EPOCH=… rpm --root /mnt/rootfs -i --ignorearch --noscripts
--notriggers --excludedocs /rpms/${TARGETARCH}/*.rpm`; stage the package files (`rpm -ql`) and
`var/lib/rpm` into one directory; final `FROM micro` + one `COPY` of that directory. No
`microdnf`, no network in `RUN`, no target-arch code executed, so arm64 builds on x64 without
QEMU, and a pinned version keeps building after UBI drops it from metadata (while the CDN keeps
the file). Works on buildah ≥ 1.33 (`ADD --checksum`) — check OpenShift's version.

**Keep the rpmdb correct.** Always copy the updated `var/lib/rpm` with the files; scanners read
nothing else (unrecorded files were invisible to Trivy and Syft). The project's CycloneDX SBOM
can list the locked RPMs (purl `pkg:rpm/redhat/zlib@1.2.11-40.el9?arch=…`) as a supplement only.

**Update via Renovate.** Upstream Renovate cannot regenerate such a lock. Options, best first:
(1) a scheduled CI job running `./gradlew microLock` and opening a PR (works everywhere);
(2) Konflux MintMaker if the lock is a real `rpms.lock.yaml` beside an `rpms.in.yaml`;
(3) a `customManagers` regex on the lock's `evr` with `datasource: rpm` and
`registryUrl: …/<arch>/baseos/os/repodata/` to *detect* updates, plus a `postUpgradeTasks`
`./gradlew microLock` to refill URLs/checksums — needs a self-hosted Renovate with
`allowedCommands`. Pinning the micro base by digest stays with Renovate's normal docker
datasource.
