# Backlog

Work that has been asked for and not yet done, newest decisions first. Move an item to the commit
log, not to a "done" list, when it lands.

Last updated 2026-11-02.

## The runtime published apart from the app

Done 2026-10-30: `<app>` and `<app>-runtime` publications, a byte-identical runtime archive, no RPM
upload; 2026-10-31: the host out of `jlink<T>`'s cache key (only whether it is Windows); 2026-11-02: the runtime's fingerprint, published beside it, and `appVerifyRuntimeFingerprint` before `publish`. Still open:

| Item | Notes |
|---|---|
| **The same runtime from a Windows agent** | Its jlink writes CRLF into `release` and backslashed pointer files for duplicate legal notices (both could be normalised in `customiseRuntime`), and `generate-jli-classes` generates `BoundMethodHandle$Species_*` differently; only `--disable-plugin generate-jli-classes`, which changes every runtime, would remove that. Find why first: the record lists each resource's SHA-256 |
| **Measure the saving** | Runtime archive size × apps × retained versions, against the app archives, from a real repository's Storage Summary |
| **An SBOM for the runtime module** | The app's SBOM lists the runtime; the runtime module carries none of its own |
| **CDS on the host** | Not linked into the runtime (`--generate-cds-archive` is not deterministic). `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=<data dir>/app.jsa` in the launcher would build one on the host on first start |

## Next: Dockerfiles parsed and rewritten with OpenRewrite

Decided 2026-10-27: one engine creates and updates the Dockerfile. `org.openrewrite:rewrite-docker`
(Apache 2.0, on Maven Central) parses it into a lossless tree; a visitor reads what the user chose
(ports, user, entrypoint, extra instructions); a standard's recipes (`oci`, `openshift`,
`redhat-certified`, as YAML) change only what the standard requires; a new file is a one-line
`FROM` seed run through the same recipes. Replaces `DockerfileTemplate`, `OwnDockerfile`,
`UpdateDockerfile` and the managed block. A spike (2026-10-27) printed six real Dockerfiles back
byte for byte, including heredocs, the escape directive and CRLF, and ran in an isolated worker
under the configuration cache.

| Item | Notes |
|---|---|
| **Engine** | Isolated worker (`classLoaderIsolation`, like the Sigstore verifier): rewrite-core brings Jackson, ASM, JNA, Caffeine, Micrometer and ANTLR. Custom recipes need `javaParameters = true` or their YAML options arrive as null. Strip `~~>` search markers before writing and report them as findings. Lines added to a CRLF file come out LF: normalise |
| **Recipes to write** | Built-ins cover labels, exec-form entrypoint, `ADD` to `COPY`, non-root user. Missing: ensure an instruction (`EXPOSE`, `ENTRYPOINT`), group-0 ownership on `COPY`, ports below 1024, `/licenses`, merging labels into an existing `LABEL` |
| **Tasks** | `appDockerfileCheck` (diff of suggestions, fails when strict) and `appDockerfileFix` |
| **CI cross-checks** | hadolint and Red Hat `preflight check container` on the sample image (the Spring Boot sample's `OpenShiftTest` already runs it as a random UID in group 0 with no capabilities). Versions in the catalogue with `# renovate:` comments |
| **`USER` per platform** | Decided 2026-10-28: the default Dockerfile keeps `USER 1001`, so plain Docker and Kubernetes don't run it as root and Red Hat's `RunAsNonRoot` check passes; `Dockerfile.openshift` has none, as OpenShift assigns the user |
| **OCI labels with the commit** | Keep `org.opencontainers.image.revision` and the rest of the OCI set; add `io.k8s`/`io.openshift` labels for OpenShift |
| **Volume contract** | The image creates `/var/lib/app/data` and `/logs` writable by user 1001 and group 0; document the volume and env-var contract with a sample `Deployment` |
| **Variants from the engine** | `Dockerfile.openshift` and `Dockerfile.micro` are hand-written in the Spring Boot sample; the engine's `openshift` and `redhat-certified` standards should write them, so they follow plugin releases as the managed block does |
| **Status 2026-11-02** | Not started past the spike. `rewrite-docker` is still at 8.90.4 (2026-08-24); two parser fixes are on main, unreleased (whitespace before commas in JSON arrays, `=` in `COPY` paths). None of the spike's problems (CRLF, `AddUserInstruction` placement, label merging, inline markers) has an upstream issue, and none of the recipes we need exists upstream: ours to write, or to contribute |
| **Packages for UBI micro** | Done in the working tree: `images {}` locks and installs them (`docs/container-testing.md`). Open-source alternatives checked 2026-11-02 (`research_notes/Dockerfile DSL design/builders.md`): none installs RPMs on a pinned micro base with its rpmdb intact, so the DSL stays, thin |

## Images from `images {}`

Asked for 2026-11-02.

| Item | Notes |
|---|---|
| **CPU's client** | `CapacityTest` passes with curl in a container of its own: 47-48 µs measured by hand across fresh containers, 26-29 µs beside busy ones. A client in the test's JVM through the port forward gave 54, 80, 60 µs |
| **Detect a busy agent** | Agreeing rounds don't reveal load (see above). `nr_throttled` and a calibration loop might; unexplored |
| **Base images from the binary repository** | `ubiMicro` and `ubiMinimal` name `registry.access.redhat.com`, which a corporate network can't reach. Today it takes `from(…)` and `--build-arg BUILD_IMAGE=…`; a `binaryRepo.repo.docker` setting should rewrite both |
| **Retire the hand-written micro files** | Once `UbiMicroTest` has passed: drop `Dockerfile.micro` and `Dockerfile.jre` from the sample for `images {}`. `runtimeOnly()` still labels with the app's version, where `Dockerfile.jre` uses Java's |
| **A fingerprint for the libraries archive** | `appVerifyRuntimeFingerprint` guards the runtime only. Libraries' bytes can't change while their jars don't, short of a change to the plugin, so this is lower value |
| **A `production` rung by default** | `docs/caching.md` shows how to add a compiler-free top module set. Making it a default set needs the list checked on every release from 11 to 27 |
| **A framework's libraries in their own layer** | Below the app's other libraries, when many apps share one BOM |

## Next: sizing CPU and memory

Asked for 2026-11-02. `docs/capacity.md` and the sample's `CapacityTest` show how by hand; the task below is still to build. A research pass (partly from memory, to verify) suggests:

| Item | Notes |
|---|---|
| **Measure** | `appSize<Target>`: start the image under Testcontainers with a memory and CPU limit, warm up, drive load (an `HttpClient` loop by default; Gatling or k6 optional), read cgroup v2 `memory.peak` and `cpu.stat` (usage, throttling), heap, metaspace and threads from Actuator or JFR (`jdk.jfr` is in the runtime; `jcmd` is not) |
| **Recommend** | Requests and limits as a resources snippet: memory from peak plus headroom, CPU request from steady-state usage and no CPU limit by default (throttling); JVM flags from the Paketo memory calculator's formula (heap = limit − metaspace from class count − threads × stack − code cache − direct − headroom), `ActiveProcessorCount`, and the GC (under 2 CPUs or 1792 MB the JVM picks Serial); `--max-old-space-size` for Node.js |
| **Guard** | Fail when the heap percentage of the limit plus measured non-heap exceeds the limit, or the run was throttled or OOM-killed |
| **Limits of it** | A build agent is not production: record the agent with the result, report startup apart from steady state, and treat the output as a starting point for VPA (`Off` mode) or KRR to refine. In-place pod resize is stable in Kubernetes 1.35, with a startup CPU boost in VPA |

## Shared module sets

| Item | Notes |
|---|---|
| **Sets from real data** | The four default sets are estimates from the samples and common stacks. Count the distinct `MODULES=` lines of published archives' `runtime/release` in the binary repository and retune |
| **Sets on other releases** | The sets use modules believed present in Java 11 to 27, but were only linked on Java 21. Link each set on every supported feature release in a test or CI job |

## AI tooling

| Item | Notes |
|---|---|
| **This repository's `.claude/settings.json`** | Builds using the plugins now get the marketplace entry from `aiSkills`; this repository's own file (the sandbox blocks writing it) still needs the same `extraKnownMarketplaces` and `enabledPlugins` lines. Owner to add |
| **Unverified hook details** | Researched 2026-10-25 from GitHub's and Devin's hook docs: Copilot runs hooks in its CLI and cloud agent only (none documented for VS Code, Visual Studio or JetBrains); Devin's edit tools are `write`, `edit`, `apply_patch` and its PostToolUse feedback is `hookSpecificOutput.additionalContext`, both now used. Still undocumented anywhere: the file-path argument names of Copilot's `edit`/`create` and Devin's `write`/`edit`, and the shape of Devin's `apply_patch` input (the script tries `file_path`, `path` and `*** Update File:` lines), and whether Devin's cloud sessions run hooks. Settle by logging one real payload from each agent |

## Code quality

| Item | Notes |
|---|---|
| **Build speed** | Measured 2026-10-25: Java's functional tests were 95% of test time (830 s summed, against 45 s for everything else). Half of it was the fixture gzipping a copy of the test JDK for every target of every test; it now builds one archive per test JVM and hard-links it (830 s to 400 s; the full `check` takes about 2 minutes here). `--profile` on the fixture builds puts what is left in `jlink` (about 5 s a build), JDK extraction (about 3 s) and the smoke test. Next, if needed: a shared build cache for the fixture builds, at the cost of tests that assert tasks really ran |

## Verification and CI

| Item | Notes |
|---|---|
| Windows functional tests | One functional test now runs on Windows: an installed JDK given by `javaApp.jdk.<feature>.windows-x64.path`, built, zipped and smoke-tested through the `.bat` launcher. The rest still skip there, because the fixtures build fake download sites with the system `tar` and `sh`; porting them means Windows-layout JDK and Node zips (commons-compress is already a dependency) |
| A Dockerfile that builds with no emulation | `e81ca52` put a `RUN` (data and log directories) in the generated Dockerfile, so building another CPU's image now needs QEMU (CI registers it). The context could carry the directories, made with their modes, for a `COPY --chown=1001:0` instead, and the cross-build would run nothing again |
| Micro's second pin | `Dockerfile.micro` now names UBI minimal too (`BUILD_IMAGE`, which installs zlib). Check that Renovate's Dockerfile support updates that `ARG` with the main Dockerfile's minimal pin, or tie the two together |
| Functional test not run here | `JdkSourceTest`'s "a JDK packaged as an RPM, named the organisation's way" could not reach its TestKit daemon in the sandbox on 2026-10-28; its pattern now spells `[feature]_[update]` instead of `revisionFormat`. Run it locally |
| GitHub API access for the agent | Unauthenticated API calls hit the rate limit; `gh` config is unreadable in the sandbox |

## Older open items

- **Gradle Plugin Portal**, optional on top of Maven Central (0.0.5, the latest, released there on 2026-10-27): `publishPlugins`, its own key, manual
  review.
- Signing RPMs: the plugin doesn't sign the RPMs it builds (archives are signed with Sigstore).
  dnf can check them through the repository's GPG-signed metadata (`repo_gpgcheck=1`, documented
  in docs/packaging.md); signing each package (`gpgcheck=1`) needs `rpm --addsign` in CI for now.
- Article: the "HUFs" anecdote is unsourced.
- Gradle deprecation warnings in this repository's own build: detekt 2.0.0-alpha.6 removed the
  only one due in Gradle 10 (`ReportingExtension.file`). `Configuration.setVisible`, due in Gradle 11,
  is still called by Dokka (2.2.0 and 2.3.0-Beta) and detekt 2's alpha; bump them when fixed
  releases ship, and move detekt to 2.0.0 final when it is out.
- Stale `~/.m2/repository/io/github/fiftieshousewife/platform*` folders on the owner's machine.
