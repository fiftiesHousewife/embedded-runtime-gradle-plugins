# Roadmap

What is still to build, from research done on 2026-10-25. On 2026-10-25 the owner kept the High
items below (less SBOM CVE matching, which isn't needed) and discarded the rest of the roadmap (two research passes over vendor docs, OpenJDK
JEPs, systemd, Sigstore, SLSA, Renovate and others). Items marked **verified** were checked
against the live source; the rest come from the cited documentation and still need confirming
before they are built. Effort: S (hours), M (a day or two), L (more).

## Fix first: done

All five were fixed on 2026-10-25: Temurin 24+ separate jmods (and SapMachine 24+, which ships
none, documented); runtime options can be locked; the hardening example; the systemd unit; Java 26
and 27. The queue of requested work is in [BACKLOG.md](BACKLOG.md).

## Done since the research

Also on 2026-10-25, and not repeated below: Node.js trusted CAs and TLS settings
(`nodeApp { security {} }`, `NODE_EXTRA_CA_CERTS`); the runtime policy for Node.js apps (new keys
`tlsMinVersion`, `forbiddenNodeArgs`); locked runtime options refusing
`NODE_TLS_REJECT_UNAUTHORIZED`; a Java Flight Recorder guide ([docs/performance.md](docs/performance.md))
and skill; a Docker sample whose container is built and health-checked in CI; and every target as
a standard Gradle distribution, with the custom installer replaced by RPMs; production JVM defaults
(`javaApp { runtimeDefaults {} }`).

## High

| What | Why | Effort |
|---|---|---|
| **Sigstore signing and SLSA provenance:** cosign bundles for archives, RPMs and images; a CI recipe with GitHub artifact attestations; signature checks before installation | `.sha256` from the same server gives integrity, not authenticity; Maven Central now validates Sigstore bundles. Keep PGP for air-gapped sites. The checks belong in the RPM install and in `app-smoke-test`, now the deployment path | M |
| **Runtime end-of-life and patch tracking:** a `checkRuntimeSupport` task (endoflife.date API), a Renovate preset for the pins and overrides, a documented rebuild cadence for each JDK CPU and Node security release | Embedding a runtime makes patching the app team's job; nothing reminds them | S–M |
| **Container image:** document that Node apps handle SIGTERM (or add tini); live/ready modes for `--healthcheck` and a Kubernetes probe snippet; OCI labels `created`, `source`, `licenses`, `base.name`, `base.digest`; base image by digest; a `buildx` multi-arch recipe | `HEALTHCHECK` is dropped in OCI-format builds (Podman's default) and ignored by Kubernetes. The Docker sample now proves the image starts and passes its health check in CI; the rest is open | S–M |

