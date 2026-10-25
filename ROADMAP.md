# Roadmap

What is missing, from research done on 2026-10-25 (two research passes over vendor docs, OpenJDK
JEPs, systemd, Sigstore, SLSA, Renovate and others). Items marked **verified** were checked
against the live source; the rest come from the cited documentation and still need confirming
before they are built. Effort: S (hours), M (a day or two), L (more).

## Fix first: done

All five were fixed on 2026-10-25: Temurin 24+ separate jmods (and SapMachine 24+, which ships
none, documented); runtime options can be locked; the hardening example; the systemd unit; Java 26
and 27. The queue of requested work is in [BACKLOG.md](BACKLOG.md).

## High

| What | Why | Effort |
|---|---|---|
| **Runtime CVE matching in the SBOM:** add a CPE and vendor/build to the runtime component; move to CycloneDX 1.6/1.7; add SPDX licence ids and a `THIRD-PARTY-LICENSES` file built from the SBOM | Scanners can't match `pkg:generic` to advisories; licence notices for the JDK (GPLv2+CPE) and Node must travel with the archive; EU CRA SBOM duties approach | S–M |
| **Production JVM defaults** (`runtimeDefaults {}`): `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`, heap dumps and `hs_err` files into `APP_LOG_DIR` with tight permissions, explicit GC, optional `DisableAttachMechanism` | The JVM's defaults (25% heap, SerialGC below 2 CPUs/1.8 GB, dumps in the working directory) suit few services | S |
| **Sigstore signing and SLSA provenance:** cosign bundles for archives, RPMs and images; a CI recipe with GitHub artifact attestations; signature checks before installation | `.sha256` from the same server gives integrity, not authenticity; Maven Central now validates Sigstore bundles. Keep PGP for air-gapped sites | M |
| **Runtime end-of-life and patch tracking:** a `checkRuntimeSupport` task (endoflife.date API), a Renovate preset for the pins and overrides, a documented rebuild cadence for each JDK CPU and Node security release | Embedding a runtime makes patching the app team's job; nothing reminds them | S–M |
| **Container image:** document that Node apps handle SIGTERM (or add tini); live/ready modes for `--healthcheck` and a Kubernetes probe snippet; OCI labels `created`, `source`, `licenses`, `base.name`, `base.digest`; base image by digest; a `buildx` multi-arch recipe | `HEALTHCHECK` is dropped in OCI-format builds (Podman's default) and ignored by Kubernetes | S–M |

## Medium

| What | Why | Effort |
|---|---|---|
| DEB packages with jdeb (pure Java, Apache 2.0) | Debian and Ubuntu servers; mirrors the RPM path | M |
| State the glibc floor (Node 24 needs ≥ 2.28; Node 26 likely 2.34) in `app.json`, RPM `Requires` and docs; `linux-musl-*` targets for Alpine (Temurin and Microsoft publish musl JDKs) | Silent failures on old or musl hosts | S / M |
| FIPS 140-3 preset: BC-FIPS jars and provider order, BCFKS keystores; Semeru's OpenJCEPlusFIPS; a note that RHEL crypto-policies don't reach an embedded JDK | Regulated users ask; validation is per JDK, OS and CPU, so a preset with warnings, not a guarantee | M |
| IBM Semeru (OpenJ9) as an eighth distribution | FIPS and memory footprint; check it ships jmods | M |
| `tls {}` and `revocationChecking` settings (protocols, named groups, signature schemes, OCSP, stapling) that write security or system properties as needed | The controls regulated shops expect; must not drop JDK 27's hybrid post-quantum group | M |
| JDK 25 AOT cache (`-XX:AOTCacheOutput`) as a per-target task, skipped when cross-building | Faster startup; must be built on the target | M |
| Opt-in OpenTelemetry agent bundling (`-javaagent`, `OTEL_*` configuration) | Observability without app changes | S–M |
| Detect `java.desktop` and add `Requires: fontconfig freetype` / Dockerfile packages | AWT/font use in POI or PDF libraries fails on minimal hosts | S |
| Windows service via WinSW with a generated XML | The `.bat` launcher can't be a service | M |
| Record tzdata version in `app.json`; document rebuilds for tzdata and CA bundle updates | Both are frozen inside the embedded runtime | S |
| Repository hygiene: OpenSSF Scorecard, actions pinned by SHA, `permissions:` in CI | Supply-chain posture of the plugins themselves | S |

## Low

- `sysusers.d` file alongside the `%pre` fallback (RPM ≥ 4.19).
- Templated `<name>@.service` for several instances per host.
- CI job that builds twice and compares checksums (diffoscope on a mismatch).
- VEX statements for vulnerabilities in JDK modules the runtime omits.
- JFR-on-start recipe; `-XX:+UseCompactObjectHeaders` note for JDK 25; reject
  `-Djava.security.manager` on JDK 24+ (JEP 486); add `jdk.management.agent` to the default
  `forbiddenModules`.
- SELinux note (`restorecon` for a custom `APP_ROOT`).

## Considered and left out

Red Hat build of OpenJDK (no macOS build, needs an account; use `javaApp.jdk.*.path` with RHEL's
RPM), Dragonwell and BiSheng (Linux only), Tencent Kona (only on demand, for SM algorithms),
JetBrains Runtime (desktop), GraalVM Native Image (doesn't fit jlink, and Oracle has moved it out
of Java SE), CRaC builds (Linux only, JDK 17/21), Liberica Lite (jlink already trims), Jib and
Paketo (they bypass the one-archive model), Helm charts, apk, Homebrew, Nix, socket activation and
`sd_notify` (neither JVM nor Node supports them natively).
