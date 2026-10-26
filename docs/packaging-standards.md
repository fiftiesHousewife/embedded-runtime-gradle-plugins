# Packaging standards

The conventions each format the plugins produce follows, why, and what the plugins leave to you.
Formats a plugin does not produce are covered at the end, so the gaps are deliberate rather than
forgotten.

## Every format

| Standard | What it requires | How the plugins meet it |
|---|---|---|
| Reproducible builds ([reproducible-builds.org](https://reproducible-builds.org/docs/)) | The same source builds the same bytes | Archives have fixed timestamps and file order. `SOURCE_DATE_EPOCH` sets an RPM's build time, but Redline stamps RPM payload files with the build machine's clock, so RPMs are not byte-identical |
| [CycloneDX](https://cyclonedx.org/specification/overview/) SBOM | A machine-readable list of every component | `META-INF/sbom.cdx.json` in every archive and published next to it, listing the runtime as well as the libraries |
| [Package URL](https://github.com/package-url/purl-spec) | One identifier per component across ecosystems | Every SBOM component has a purl: `pkg:maven/…`, `pkg:npm/…`, `pkg:generic/…` for the runtime |
| [Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html) | Variants described by attributes, not file names | One variant per target, with `org.gradle.native.operatingSystem` and `org.gradle.native.architecture` |
| Checksums | A digest beside every published file | Gradle publishes `.sha256` and `.sha512` with each file; Gradle verifies them when it resolves an archive, e.g. for `app-smoke-test` |
| [OpenPGP signatures](https://www.rfc-editor.org/rfc/rfc9580) | Detached `.asc` beside every file, for Maven Central | `-PsigningKey` and `-PsigningPassword` sign the whole publication |
| [OCI image annotations](https://github.com/opencontainers/image-spec/blob/main/annotations.md) | `org.opencontainers.image.*` labels | The generated Dockerfile sets `title`, `version`, `revision`, `created`, `source`, `licenses`, `base.name` and `base.digest` |

## Archives: tar.gz, tar and zip

* **One top-level directory**, `<app>-<version>-<target>/`, so extracting never scatters files into
  the current directory, and two versions or targets can sit side by side.
* **Unix permissions are recorded** in tar and zip alike, so `bin/<app>` and the runtime's
  binaries stay executable. Windows ignores them.
* **`tar.gz` is the default** for every target: Linux and macOS have `tar`, Windows 10 and later has
  `tar.exe`. `zip` suits teams that hand Windows builds to people using Explorer; `tar` suits
  pipelines that compress or layer the archive themselves.
* **The file name** is `<app>-<version>-<target>.<ext>`, Gradle's own `base-version-classifier`
  convention, so the Maven classifier and the file name cannot disagree.

### The macOS archive

A Mac app delivered to servers or developers' machines is a tarball like any other target's:
`mac-aarch64` or `mac-x64`. Apple's own formats serve a different purpose:

| Format | For | Why the plugins don't build it |
|---|---|---|
| `.app` bundle in a `.dmg` | Desktop apps a user drags into Applications | A server process has no bundle |
| `.pkg` (`pkgbuild`/`productbuild`) | Installers that put files in system locations | Needs a Mac to build; to run without warnings it must be signed with an Apple Developer ID *Installer* certificate and **notarized** with `notarytool` |

If a `.pkg` is wanted, it can wrap the Mac archive on a Mac CI agent:
`pkgbuild --root <extracted archive> --install-location /opt/<app> --identifier com.example.<app> --version <v> <app>.pkg`,
then `productsign` and `xcrun notarytool submit --wait`. Binaries inside a notarized package must
themselves be signed with the hardened runtime, which includes every executable and `.dylib` in
the embedded JDK.

## RPM

Built by `appRpm<Target>` for each Linux target, following the
[Fedora packaging guidelines](https://docs.fedoraproject.org/en-US/packaging-guidelines/) and the
[Filesystem Hierarchy Standard](https://refspecs.linuxfoundation.org/FHS_3.0/fhs/index.html), which
RHEL, Rocky, Alma, Oracle Linux, Amazon Linux and SUSE follow.

### Name and version

* **File name:** `<name>-<version>-<release>.<arch>.rpm`, e.g. `orders-1.4.2-1.x86_64.rpm`. dnf
  and every repository tool parse this.
* **Architectures** use RPM's names, not Java's or Node's: `x86_64` for `linux-x64`, `aarch64` for
  `linux-aarch64`.
* **Version** cannot contain `-`, which separates version from release. A Maven qualifier becomes a
  tilde: `1.4.2-SNAPSHOT` is packaged as `1.4.2~SNAPSHOT`, which RPM sorts *before* `1.4.2`, just as
  Maven does.
* **Release** starts at `1` and goes up when the same version is repackaged (for example after a
  packaging fix).

### Where files go

| Path | Holds | Owner, mode |
|---|---|---|
| `/opt/<name>/` | The app directory exactly as in the archive. FHS: `/opt` is for self-contained add-on packages, which an app with its own runtime is | `root`, read-only to the service |
| `/etc/<name>/` | `conf.defaults/`, marked **`%config(noreplace)`**; an optional `env` file | `root:<user>`, `0750`, files `0640` |
| `/var/lib/<name>/` | Data (`APP_DATA_DIR`, and `APP_ROOT`) | `<user>`, `0750` |
| `/var/log/<name>/` | Logs (`APP_LOG_DIR`) | `<user>`, `0750` |
| `/usr/lib/systemd/system/<name>.service` | The unit. `/usr/lib` is for the package; local changes go in `/etc/systemd/system/<name>.service.d/` via `systemctl edit` | `root`, `0644` |

The package owns only its own directories, never `/opt`, `/etc` or `/usr/lib/systemd/system`, so
removing it never removes a shared directory.

`%config(noreplace)` is the RPM version of "seed configuration once, never overwrite it": on upgrade,
an edited file stays and the new default is written beside it as `<file>.rpmnew`; an unedited file
is updated.

### Service account and unit

* A **system account** named after the package (`rpm { user = … }` to change it), created in the
  `%pre` scriptlet with `useradd -r -s /sbin/nologin`. The package requires `shadow-utils` for it.
  It is not removed on uninstall, as the Fedora guidelines advise, because files it owns may remain.
* **Scriptlets** are the expansions of `%systemd_post`, `%systemd_preun` and
  `%systemd_postun_with_restart`: preset on first install, stop and disable on removal, restart on
  upgrade. Whether the service is enabled is left to the host's preset policy, so install with
  `dnf install` then `systemctl enable --now <name>`.
* **The unit is hardened** with `NoNewPrivileges`, `PrivateTmp`, `ProtectSystem=strict`,
  `ProtectHome` and write access only to the data and log directories. `systemd-analyze security <name>`
  scores it.

### Signing and repositories

* **Package signatures** are added after the build with `rpmsign --addsign` (from `rpm-sign`) on a
  machine holding the key, or by the repository: Artifactory and Nexus can sign repository metadata
  with a GPG key. dnf checks them when the repository's `.repo` file says `gpgcheck=1`.
* **YUM/DNF repositories** are a directory of RPMs plus `repodata/` (`repomd.xml` and the primary,
  filelists and other indexes). Artifactory and Nexus RPM repositories regenerate `repodata/` on
  every upload, which is what `appPublishRpm` relies on. For a plain web server, copy the RPMs and run
  `createrepo_c --update <dir>`.
* **Rollback** is `dnf history undo <id>` or `dnf downgrade <name>-<old version>`, provided the
  repository keeps old versions.

## Formats the plugins do not build

| Format | Standard | Notes |
|---|---|---|
| **DEB** (Debian, Ubuntu) | [Debian Policy](https://www.debian.org/doc/debian-policy/) | Named `<name>_<version>-<revision>_<arch>.deb` with `amd64`/`arm64`; configuration listed in `conffiles`; same FHS layout. The pure-Java `jdeb` library would build it the way Redline builds RPMs. Not built until someone needs it |
| **jpackage** installers (`.msi`, `.pkg`, `.deb`, `.rpm`) | [JEP 392](https://openjdk.org/jeps/392) | Builds only for the OS it runs on, so it can't make a Linux package on a Mac; aimed at desktop apps; Java only |
| **Windows MSI** | Windows Installer | Needs WiX on a Windows machine; a Windows service also needs a service wrapper (the launcher is a `.bat`) |
| **Container images** | [OCI image spec](https://github.com/opencontainers/image-spec) | `appDockerContext` prepares the build context; building and pushing the image is left to `docker build`, Buildah or your CI's builder |
