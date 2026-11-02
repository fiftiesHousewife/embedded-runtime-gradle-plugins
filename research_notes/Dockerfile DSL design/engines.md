# Dockerfile engines: parse, view, apply a standard

Researched 2026-11-02 against live sources (Maven Central directory listings and metadata, GitHub API
and raw files, vendor docs). Anything not fetched is marked UNVERIFIED. Context: replace the template
plus managed block plus hand-written variants with one engine that creates a new Dockerfile and
updates a user-edited one without overwriting the user's choices. The 2026-10-27 spike chose
OpenRewrite `org.openrewrite:rewrite-docker`.

## 0. The finding that changes the picture: OpenRewrite left Maven Central

- Maven Central's newest `rewrite-core` and `rewrite-docker` is **8.90.4, 2026-08-24**
  (`maven-metadata.xml` `<lastUpdated>20260824231718`; 8.91.0 and 8.96.5 POMs return 404).
  <https://repo1.maven.org/maven2/org/openrewrite/rewrite-docker/>
- GitHub has tags **v8.91.0 (2026-08-26) to v8.96.5 (2026-11-01)**, about 45 releases since.
  <https://github.com/openrewrite/rewrite/releases>
- Those go only to the **Code Genome Project** (CGP), `https://artifacts.codegenomeproject.org/maven`,
  which **requires sign-in and a download token**. "Earlier OpenRewrite releases remain on Maven
  Central." "OpenRewrite's Apache-licensed recipes and the Moderne CLI are available to any
  authenticated user." <https://docs.openrewrite.org/running-recipes/getting-started>
- The repository's own commits confirm it: "Releases are no longer published from GitHub Actions",
  `publish.yml` "turned off since 2026-10-23: CGP's release train publishes them" (commit 185122c4,
  2026-10-25), and PR #8741 (merged 2026-09-02) adds CGP to `pluginManagement` because
  `rewrite-build-gradle-plugin` depends on "rewrite releases published only to the Code Genome Project".
  <https://github.com/openrewrite/rewrite/pull/8741>
- A third party notes `rewrite-recipe-bom` 3.38.0 "reached Central by mistake" and depends on
  `rewrite-bom` 8.91.0, which is only on CGP. <https://github.com/atunko-dev/atunko/pull/158>

Consequence for this plugin: it ships through Maven Central and promises `--offline`, verification
metadata and corporate mirrors. Depending on rewrite-docker means either freezing on 8.90.4
(Apache 2.0, still on Central, never getting fixes) or making every consuming build authenticate
to a vendor repository. Neither fits. Forking (Apache 2.0 allows it) means owning an ANTLR grammar
plus rewrite-core.

## 1. OpenRewrite rewrite-docker in depth

**What:** an OpenRewrite language module: ANTLR 4.13.2 grammar (`DockerLexer.g4`, `DockerParser.g4`),
a lossless tree (`Docker.java`), printer, visitors, about 9 recipes and 7 search recipes. Lives in the
core repo since **8.72.3 (2026-01-27)**; the earlier `org.openrewrite.recipe:rewrite-docker`
(last 2.14.5, 2026-01-20) repository is archived. Jim Schubert's `jimschubert/rewrite-docker`
(JitPack, "pet project... does not work well enough for production") is the ancestor; not on Central.

**Licence:** Apache 2.0 (file headers in `rewrite-docker/src/main/java/...`; the repo's JavaScript etc.
modules are under the Moderne Source Available License, the core modules are not).
<https://docs.openrewrite.org/licensing/openrewrite-licensing>

**Maintenance:** active in the repo but mostly automated since August: commits to `rewrite-docker`
since 2026-08-26 are weekly `[Auto] EOL Docker images`, one fix (#9188, 2026-10-14: whitespace
before commas in JSON arrays, `=` in ADD/COPY paths) and repo-wide changes.
<https://github.com/openrewrite/rewrite/commits/main/rewrite-docker>
Important history: on 2026-08-22 a 32-commit rework landed (heredoc opened by any word, quoted
delimiters, `<<-`, honouring `# escape=`, "read a line the way Docker joins it", "assert a tree models
what it prints"), motivated by "round-trip bugs found in a 1,432-file Dockerfile corpus"; it was
**reverted on 2026-08-24** (#8636, "clears the slate so the work can be reintroduced under review"),
and the reintroduction PR #8637 was **closed unmerged** the same day. So 8.90.4 (released after the
revert) has the older parser with those known round-trip gaps.
<https://github.com/openrewrite/rewrite/pull/8636> <https://github.com/openrewrite/rewrite/pull/8637>

**Tree API** (`rewrite-docker/src/main/java/org/openrewrite/docker/tree/Docker.java`, 1052 lines):
- `Docker.File` (SourceFile: `globalArgs: List<Arg>`, `stages: List<Stage>`, `eof: Space`, charset,
  BOM flag); `Stage` (`from: From`, `instructions: List<Instruction>`).
- Instructions: `From` (+`As`), `Run`, `Add`, `Copy`, `Arg`, `Env` (`EnvPair`), `Label`
  (`LabelPair{key, hasEquals, value}` so the legacy `key value` form round-trips), `Cmd`,
  `Entrypoint`, `Expose` (`Port`, `Protocol`), `Volume`, `Shell`, `Workdir`, `User`, `Stopsignal`,
  `Onbuild`, `Healthcheck`, `Maintainer`.
- Forms: `ShellForm`, `ExecForm`, `HeredocForm` + `HeredocBody`, `CopyShellForm`; `Flag`,
  `Argument` of `Literal` (with `QuoteStyle`) and `EnvironmentVariable`.
- Every node has `Space prefix` (whitespace + `List<Comment>`), `Markers`, immutable `@With` withers.
  `Space` treats `\` (or backtick) + newline, including `\r\n`, as whitespace, so continuations are kept.
- Traits: `DockerFrom`, `DockerCopyFrom`, `DockerImageReference` (matchers over image references).

**Writing a recipe:** subclass `Recipe` with `@Option` fields; `getVisitor()` returns a
`DockerIsoVisitor<ExecutionContext>` overriding e.g. `visitStage`, `visitLabel`; return
`stage.withInstructions(ListUtils.insert(...))`. Built-ins confirm the spike's observations:
`AddUserInstruction` appends with `ListUtils.concat(stage.getInstructions(), user)` (i.e. after
ENTRYPOINT/CMD); `AddOrUpdateLabel` (key, value, overwriteExisting, stageName) inserts a *new*
`LABEL` after the last existing label rather than merging into it; `AddOciLabels` exists.
Search recipes (`FindExposedPorts`, `FindRootUser`, `FindUnpinnedBaseImages`, `FindMissingHealthcheck`,
`FindEndOfLifeImages`, `FindBaseImages`) mark with `SearchResult`, which the default printer shows as
`~~>`; print with `PrintOutputCapture.MarkerPrinter.SANITIZED` (exists in `PrintOutputCapture`) or
don't run search recipes in the mutating pass.

**Declarative YAML:** `META-INF/rewrite/docker.yml` defines `type: specs.openrewrite.org/v1beta/recipe`
composites (`DockerBestPractices`, `DockerSecurityBestPractices` with `AddUserInstruction: {userName:
appuser}`, `DockerBuildOptimization`). A "standard" could be such a YAML list of parameterised recipes,
loaded with `Environment.builder().load(new YamlResourceLoader(in, uri, props)).build()
.activateRecipes(name)`.

**Programmatic run without the Gradle plugin** (APIs verified in rewrite-core main):
```java
ExecutionContext ctx = new InMemoryExecutionContext(Throwable::printStackTrace);
List<SourceFile> files = DockerParser.builder().build()
    .parse(List.of(Parser.Input.fromFile(path)), baseDir, ctx).toList();
RecipeRun run = recipe.run(new InMemoryLargeSourceSet(files), ctx);
for (Result r : run.getChangeset().getAllResults()) { r.getAfter().printAll(); r.diff(); }
```
`DockerParser implements Parser` (`parseInputs`, `accept(Path)` by file-name pattern, `builder()`).

**RewriteTest:** `rewrite-test`'s `RewriteTest` with `org.openrewrite.docker.Assertions.docker(before,
after)` SourceSpecs; `rewriteRun(spec -> spec.recipe(...), docker("...", "..."))` asserts output and
idempotence (and now recipe quality checks, per 8.96.5 notes).

**Lossless?** Yes by design (prefix spaces, comments, continuations, heredoc bodies, quote styles), but
the spike found CRLF inserted lines come out LF (new nodes get `\n` prefixes; nothing infers the file's
line separator), and the reverted work shows the published parser still has round-trip edge cases.

**Weight:** rewrite-core pulls jgit, Jackson (core, databind, smile, yaml, jsr310), ASM x4, gizmo,
java-object-diff, jna-platform, univocity, caffeine, commons-text, micrometer, snakeyaml, jsonrpc,
objenesis; rewrite-docker adds antlr4-runtime. Needs a Worker API classloader-isolated worker to avoid
clashing with the build's own Jackson/ASM.

**Fit:** technically the best JVM parser available; strategically poor now (CGP-only releases, frozen
public version, reverted parser work, heavy deps). Usable only as "pin 8.90.4 forever" or "vendor/fork".

## 2. Red Hat's own tooling (precedent)

**dockerfile-parse** (Python, BSD-3-Clause), <https://github.com/containerbuildsystem/dockerfile-parse>.
Latest PyPI 2.0.1, **2023-07-18**; last push 2024-08. Line-based: `structure` gives instruction,
`startline`/`endline`, `content`, `value`; setters `baseimage`, `parent_images`, `labels`, `envs`,
`args`, `add_lines(..., all_stages, at_start)`, `add_lines_at`. Writes back by replacing the whole
line range of the instruction it changes (`lines[startline:endline+1] = [content]`), so untouched lines
are byte-identical and comments survive, but an edited multi-line instruction is rewritten as one line,
and there is no heredoc handling in `parser.py`. Not JVM.

**OSBS / atomic-reactor** (BSD-3, 4.22.1, 2026-05-25) uses it in plugins `add_labels_in_df`,
`change_from_in_df`, `add_buildargs_in_df`, `inject_parent_image`, `inject_yum_repos`, `add_help`,
`add_image_content_manifest`. <https://github.com/containerbuildsystem/atomic-reactor/tree/master/atomic_reactor/plugins>

**Konflux:** buildah tasks moved (2026-10-26) from `konflux-ci/build-definitions` to
`konflux-ci/container-build-catalog` (PR #3350 closed: "Task moved to a different repo").
<https://github.com/konflux-ci/container-build-catalog/tree/main/task/buildah>. Since 0.10 the build
step is the Go `konflux-build-cli image build`. From its CHANGELOG (latest 0.14.1):
- Labels and annotations are passed as build flags (`--labels`, `--annotations`, default vcs labels
  `org.opencontainers.image.revision`/`vcs-ref`, `org.opencontainers.image.source`/`vcs-url`), not
  written into the Dockerfile; injected labels take precedence over the Dockerfile's.
- `labels.json` and `content-sets.json` are injected into `/usr/share/buildinfo` (and `/root/buildinfo`)
  "using a separate build-context", without writing files into the source directory.
- It *does* modify the containerfile in one place: prefetch (Hermeto) env vars are injected into RUN
  instructions; it "no longer mangles RUN instructions that use the exec form or a bare here-doc.
  Instead skips the instruction and logs a warning"; handles backtick `# escape=`.
- `ENV_VARS` param injects ENV; yum repo files injected into `/etc/yum.repos.d`; base images (including
  `COPY --from` / `RUN --mount=from`) resolved to digests (`--resolved-base-images-output`).
- Parsing uses `konflux-ci/dockerfile-json` (Go, BuildKit's parser, buildkit v0.19.0).
- (Earlier UNSET_LABELS proposal for inherited base-image labels not implemented.)

**Precedent:** Red Hat's pipelines keep the user's Dockerfile as the source of truth and add the
standard *around* it: labels and annotations by `--label`, metadata files through an extra build
context, base images pinned by digest at build time, and only narrowly-scoped textual edits of a
build-time copy, skipping forms they can't edit safely. None of them round-trips a full AST.

## 3. Other parsers

| Parser | Licence | Latest | Lossless round trip | Modify + re-print | JVM |
|---|---|---|---|---|---|
| BuildKit `frontend/dockerfile/parser` (Go) | Apache 2.0 | buildkit v0.34.1, 2026-10-20; dockerfile 1.28.0-labs 2026-10-07 | No printer. `Node{Value, Next, Children, Heredocs, Original, Flags, StartLine, EndLine, PrevComment}`: positions and original line only | No | No (Go; via a CLI like dockerfile-json) |
| rcjsuen/dockerfile-ast (TS) | MIT | npm 0.7.1, 2025-07-21; repo push 2026-04 | Keeps comments (also inside multi-line instructions), escape directive, ranges; standalone `\r` not a delimiter | Read-only AST with ranges; edits would be text splices by range | No (Node) |
| hadolint/language-docker (Haskell) | GPL-3 | v16.0.0, 2026-07-29 | Pretty-printer, not documented as preserving formatting (UNVERIFIED either way) | Yes, re-prints | No; GPL |
| docker-java | Apache 2.0 | 3.7.1 | Its Dockerfile class only scans for build context, not an editing AST (UNVERIFIED in detail) | No | Yes |
| jimschubert/rewrite-docker | Apache 2.0 | JitPack, push 2025-10 | ancestor of rewrite-docker | via OpenRewrite | Yes, not on Central |

No other JVM Dockerfile AST with a lossless printer was found on Maven Central.

## 4. Generators instead of parsing

- **bmuschko gradle-docker-plugin** (Apache 2.0): 10.0.0 on the Plugin Portal (lastUpdated
  2025-10-30, requires Gradle 9.2); repo last push 2025-10-30, slow. `Dockerfile` task: `from`,
  `copyFile`, `runCommand`, `label`, `exposePort`, `entryPoint`, `defaultCommand`,
  `environmentVariable`, `instruction(raw)`, `instructionsFromTemplate`; multi-stage since 3.4.0.
  Generates only: cannot read or merge a user's Dockerfile. Can express a UBI micro `--installroot`
  stage only as raw `RUN` strings. <https://bmuschko.github.io/gradle-docker-plugin/current/user-guide/>
- **Eclipse JKube** (EPL-2.0): 1.20.0, 2026-07-21 (Maven plugins and `org.eclipse.jkube:gradle-plugin`).
  `DockerFileBuilder` emits a single-stage file from `from`, assembly, `runCmds`, `user`, ports,
  labels, env, volumes, healthcheck; maintainers say several `USER` lines aren't possible
  (<https://github.com/eclipse-jkube/jkube/discussions/2703>). Or uses a user Dockerfile verbatim
  (`${}` filtering). OpenShift: S2I / BuildConfig / ImageStream, not Dockerfile edits. No multi-stage
  generation, so no `--installroot` micro stage.
- **Jib** (Apache 2.0): Gradle plugin 3.5.4, 2026-07-14. No Dockerfile, no RUN, so no dnf; can base on
  UBI and set user/ports/labels; can't produce the RPM-installed micro image.
- **Spring Boot buildpacks** (`bootBuildImage`, Boot 4.1.2 docs): default builder
  `paketobuildpacks/builder-noble-java-tiny`; no Dockerfile. Paketo has `paketobuildpacks/ubi-9-builder`
  and `run-java-*-ubi9-base` images. No arbitrary dnf stage.
- **Dagger** (Apache 2.0): v0.21.11, 2026-10-21. Container API (`from().withExec().withDirectory()`)
  can express any multi-stage `--installroot` build, but needs the Dagger engine (a container) and the
  Java SDK README says "experimental. Please do not use it for anything mission-critical"; no
  `io.dagger` group found on Maven Central. Produces images, not Dockerfiles.
- **Docker Bake** (HCL/JSON/Compose): orchestrates builds of existing Dockerfiles (targets, args,
  tags; labels/platforms/annotations also, UNVERIFIED on the fetched page). Doesn't generate or edit
  Dockerfiles; a useful *output* for multi-variant builds.
- **BuildKit LLB / `# syntax=` frontends:** BuildKit loads frontends from images named on the first
  line (`# syntax=docker/dockerfile:1`, labs channel). A custom frontend could apply the standard at
  build time, but needs BuildKit (not Podman/buildah, which OpenShift and Konflux use) and Go.
- **Earthly:** "no longer actively maintained"; Earthly Cloud shut down 2025-07-16; last release
  v0.8.16 (2025-07-16); community fork EarthBuild. Excluded.

UBI micro reality check: Red Hat documents `dnf install --installroot` into a mounted ubi-micro (via
buildah) and the catalog says micro is filled "using the package manager on the underlying host,
typically using Buildah, or Multi-stage builds". Only Dockerfile-based tools (or Dagger) can express it.

## 5. Standards to encode or cross-check

**hadolint** (GPL-3; v2.15.1, 2026-07-31): rules DL1001, DL3000-DL3068, DL4000-DL4006 plus ShellCheck.
Most relevant: DL3002 last USER not root, DL3006/DL3007 tags, DL3020 COPY not ADD, DL3025 JSON CMD,
DL3026 trusted registries, DL3038/DL3040/DL3041 dnf `-y`/`clean all`/versions, DL3048-DL3058 label
schema (`label-schema` types text/url/semver/hash/rfc3339/spdx/email, `strict-labels`), DL3059 merge
RUNs, DL3061 order, DL3066 non-numeric USER, DL4006 pipefail. JSON and SARIF output. Run as an external
binary only (GPL). <https://github.com/hadolint/hadolint>

**Docker Build Checks** (Dockerfile 1.8+, Buildx 0.15+; `docker build --check`, `# check=skip=...;error=true`,
`BUILDKIT_DOCKERFILE_CHECK`): StageNameCasing, FromAsCasing, NoEmptyContinuation,
ConsistentInstructionCasing, DuplicateStageName, ReservedStageName, JSONArgsRecommended,
MaintainerDeprecated, UndefinedArgInFrom, WorkdirRelativePath, UndefinedVar,
MultipleInstructionsDisallowed, LegacyKeyValueFormat, RedundantTargetPlatform, SecretsUsedInArgOrEnv,
InvalidDefaultArgInFrom, FromPlatformFlagConstDisallowed, CopyIgnoredFile, InvalidDefinitionDescription
(experimental), ExposeProtoCasing, ExposeInvalidFormat. <https://docs.docker.com/reference/build-checks/>
Note LegacyKeyValueFormat: an engine adding labels must write `key=value`.

**Red Hat preflight** (Apache 2.0; 1.22.0, 2026-10-27), from `internal/engine/engine.go` and
`internal/policy/container/*.go`. Policies: container, root, scratch-nonroot, scratch-root, konflux.
Container policy checks: HasLicense (`/licenses`, at least one file), HasUniqueTag, MaxLayers
(40), HasNoProhibitedPackages, HasRequiredLabels (**name, vendor, version, release, summary,
description, maintainer**), HasNoProhibitedLabels (name, vendor, maintainer must not violate the Red Hat
trademark, so inherited UBI values must be overridden), RunAsNonRoot (image config `User` must be set
and not `0`/`root`), HasModifiedFiles, BasedOnUbi, HasProhibitedContainerName,
HasSupportedRedHatBaseImage. <https://github.com/redhat-openshift-ecosystem/openshift-preflight>
Implication: "USER is irrelevant on OpenShift" is true at runtime, but certification still needs a
non-root USER, so the standard's view should treat USER as "must be non-root, numeric preferred",
not as ignorable.

**OpenShift image guidelines** (OCP 4.19 "Creating images" 4.1.2, 4.2.1): arbitrary UID in group 0,
`RUN chgrp -R 0 /some/directory && chmod -R g=u /some/directory`; don't listen below 1024; numeric USER
needed by S2I without anyuid; configure via env vars; data on volumes. Labels `io.openshift.tags`,
`io.openshift.wants`, `io.k8s.description`, `io.openshift.non-scalable`, `io.openshift.min-memory`,
`io.openshift.min-cpu` (`io.openshift.expose-services` not seen in the fetched part, UNVERIFIED).
<https://docs.redhat.com/en/documentation/openshift_container_platform/4.19/html/images/creating-images>

## 6. Recommendation for "one universal engine"

Ranked:

1. **Own small engine in app-core: line-preserving instruction model + standard as data.**
   Parse into instructions with exact source spans (keyword, flags, args, continuation and heredoc
   extents, comments, `# syntax`/`# escape`/`# check` directives, detected line separator), modelled on
   dockerfile-parse's approach but with the rigour of BuildKit's line joining. Edits are span splices:
   untouched bytes stay identical; inserted lines take the file's separator and indentation; labels
   merge into the final stage's existing LABEL (or one new `key=value` block placed before
   USER/ENTRYPOINT/CMD); USER and ports are *reported and checked*, not rewritten. Creating a new file
   = applying the standard to a minimal skeleton, so there is one path. Pure Kotlin, no dependencies,
   configuration-cache friendly, fits detekt's limits as several small types. Cross-check with
   `docker build --check` and hadolint in tests (both external), and preflight's label/licence rules
   encoded as data. Risk: we own the parser's corner cases (heredocs, escape directive, JSON forms);
   mitigate with a corpus round-trip test (the OpenRewrite authors needed 1,432 files to find theirs)
   and by refusing to edit what we can't model (Konflux's "skip and warn").
2. **Put the standard outside the file where Red Hat does:** labels/annotations as build arguments or
   generated `LABEL`s for the plugin's own Dockerfiles, metadata (`/licenses`) through the build context,
   digest pinning at build time. Combine with option 1 for the parts that must be in the file
   (group-0 ownership, ports). Low risk, matches Konflux/OSBS precedent.
3. **rewrite-docker 8.90.4 pinned, in an isolated worker** (or a fork). Best-in-class lossless tree
   today, Apache 2.0, but: no further releases on Central (CGP needs credentials), the August round-trip
   rework reverted, CRLF/label-merge/placement gaps to work around with our own visitors, about 25
   transitive libraries. Acceptable only if the plugin accepts a frozen dependency.
4. Generators (bmuschko, JKube, Jib, buildpacks): can't update a user's file; JKube/Jib/buildpacks can't
   express the micro `--installroot` stage. Dagger: experimental Java SDK, needs an engine. Earthly: dead.

Risks common to all: users' Dockerfiles that compute values at build time (ARG-driven FROM, heredoc
scripts) can't be judged statically, so the view must say "unknown" rather than guess; Red Hat
certification rules change (preflight releases about monthly), so keep the standard as data with a
version; the OpenRewrite situation shows a JVM dependency's distribution channel can disappear.
