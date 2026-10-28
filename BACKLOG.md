# Backlog

Work that has been asked for and not yet done, newest decisions first. Move an item to the commit
log, not to a "done" list, when it lands.

Last updated 2026-10-28.

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
| **Layers** | Split the single `COPY app/` into `runtime/`, `lib/` and the app, so a release doesn't re-push the 39 MB runtime; add a test that a runtime builds byte-identical twice |
| **Tasks** | `appDockerfileCheck` (diff of suggestions, fails when strict) and `appDockerfileFix` |
| **CI cross-checks** | hadolint, Red Hat `preflight check container` on the sample image, and a Testcontainers test running the image as a random UID in group 0. Versions in the catalogue with `# renovate:` comments |
| **No `USER` instruction** | Decided 2026-10-27: OpenShift ignores `USER`, so the Dockerfile has none. Plain Docker and vanilla Kubernetes then run the image as root unless the pod sets `runAsNonRoot`/`runAsUser`, and Red Hat's `RunAsNonRoot` certification check fails: say so in the docs |
| **OCI labels with the commit** | Keep `org.opencontainers.image.revision` and the rest of the OCI set; add `io.k8s`/`io.openshift` labels for OpenShift |
| **OpenShift and Oracle samples** | The Docker sample on Oracle JDK (a release Oracle's NFTC lets an archive carry), with an OpenShift Dockerfile, as the corporate default example |
| **Writable storage** | `APP_DATA_DIR`/`APP_LOG_DIR` don't exist in the image: ship them group-0 writable for the unmounted case, and document the volume and env-var contract with a sample `Deployment` |

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
