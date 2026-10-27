# Keeping the runtime patched

An app that embeds its runtime also embeds the runtime's security fixes, or the lack of them. The
server's package manager no longer patches Java or Node.js for you: a fix reaches production when
the app is rebuilt and released. This page covers when to do that and what reminds you.

## When fixes come out

| Runtime | Fixes released | Supported for |
|---|---|---|
| Java (every distribution) | The Critical Patch Update, on the third Tuesday of January, April, July and October; vendors' builds follow over a few days | Long-term-support releases (11, 17, 21, 25) for years, by vendor; others for six months |
| Node.js | Security releases as announced on [nodejs.org](https://nodejs.org/en/blog/vulnerability), usually a few a year | Even-numbered majors for 30 months; odd-numbered ones for six |

A plugin release moves every pin (each JDK distribution and Java release, Node.js and the
container base image) to the newest patch present on every target. The usual cadence is:

1. Within a week or two of each Java CPU or Node.js security release, upgrade the plugins and
   rebuild; the smoke test proves the app still starts.
2. If the fix can't wait for a plugin release, override the pin
   (`javaApp.jdk.21=21.0.13+9`, `nodeApp.node.version=24.22.1`; see [Reference](reference.md)) and
   remove the override once a plugin release catches up.
3. Move to a newer release line before the one you embed reaches end of life.

## `checkRuntimeSupport`

```bash
./gradlew checkRuntimeSupport
```
```
eclipse-temurin 21 (21.0.12.1+1): supported until 2029-12-31, latest 21.0.13+9
Warning: eclipse-temurin 21 has a newer release, 21.0.13+9, than the embedded 21.0.12.1+1; upgrade the plugin or override the pin
```

It asks [endoflife.date](https://endoflife.date) about the embedded runtime's distribution and
release line. It fails when the line has reached end of life, and warns when it will within 90
days or when a newer patch is out. The answer changes with the date, not with your code, so it is
not part of `check`: run it on a schedule, for example weekly in CI:

```yaml
# .github/workflows/runtime-support.yml
on:
  schedule: [{ cron: "0 6 * * 1" }]
  workflow_dispatch:
jobs:
  runtime-support:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: 21 }
      - run: ./gradlew checkRuntimeSupport
```

| Gradle property | Default | Does |
|---|---|---|
| `javaApp.runtimeSupportUrl` / `nodeApp.runtimeSupportUrl` | `https://endoflife.date/api/v1/products/` | Where the data comes from: a generic remote repository in your binary repository when builds can't reach the internet, or a `file:` directory holding a copy (`<dir>/nodejs`, `<dir>/eclipse-temurin`) on an air-gapped site |
| `javaApp.endOfLifeWarningDays` / `nodeApp.endOfLifeWarningDays` | `90` | How early to warn of an end of life |
| `javaApp.allowEndOfLife` / `nodeApp.allowEndOfLife` | `false` | Report an end-of-life runtime as a warning instead of failing |

Behind a corporate network, create a generic remote repository of
`https://endoflife.date/api/v1/products/` (Artifactory and Nexus both proxy plain HTTPS) and set
the URL once per machine in `~/.gradle/gradle.properties`, as with
[the binary repository](binary-repository.md).

## Renovate

Two [Renovate](https://docs.renovatebot.com) presets open a pull request when a newer patch of the
pinned runtime is out. They update overrides of the pin, in `gradle.properties` or a build script;
the plugins themselves are updated by Renovate's own Gradle support.

```json
{
  "extends": [
    "config:recommended",
    "github>fiftiesHousewife/embedded-runtime-gradle-plugins//renovate/temurin",
    "github>fiftiesHousewife/embedded-runtime-gradle-plugins//renovate/nodejs"
  ]
}
```

- [`renovate/temurin.json`](../renovate/temurin.json): `javaApp.jdk.<feature>=…` and
  `jdkVersion = "…"`, from Adoptium's releases on GitHub. Updates wait seven days, since Adoptium
  uploads a release one target at a time. For other distributions, rely on `checkRuntimeSupport`.
- [`renovate/nodejs.json`](../renovate/nodejs.json): `nodeApp.node.version=…` and
  `nodeVersion = "…"`, from nodejs.org.

Self-hosted Renovate needs a GitHub token (`RENOVATE_GITHUB_COM_TOKEN`, read-only) to look up
Adoptium's releases; the hosted Renovate app has one already.

Both stay within the release line: moving from Java 21 to 25 or Node.js 24 to 26 is a decision,
not an update.

### The container base image

The Dockerfile's base image, `registry.access.redhat.com/ubi9/ubi-minimal` pinned by tag and
digest, comes from the plugin release like the runtimes, so upgrading the plugin moves it. Teams
that pass their own with `--build-arg BASE_IMAGE=…`, a registry mirror say, pin it themselves.
[`renovate/ubi.json`](../renovate/ubi.json) is a reference example for whoever runs Renovate for
your organisation, to copy into the central configuration and adapt. It does two things:

- **Finds `BASE_IMAGE=<image>:<tag>@sha256:<digest>`** in CI configuration (GitHub Actions, GitLab
  CI, Jenkins), Makefiles, shell scripts and `gradle.properties`, mirror registries included, and
  updates tag and digest in one pull request: for a new minor (9.6 to 9.8), and for a rebuild of
  the same tag, which is how Red Hat ships security fixes, under a new digest. Renovate's Docker
  versioning keeps to tags like the current one, so `latest` and Red Hat's `9.<minor>-<build>`
  tags are never proposed.
- **Leaves the plugin's Dockerfile alone.** Renovate's own Dockerfile support finds the
  `ARG BASE_IMAGE` default in the plugin's managed block, which each plugin release rewrites, so
  the preset turns it off for that image rather than open pull requests the next build undoes.

A mirror needs the same `hostRules` credentials Renovate already uses for that registry. Check an
adapted copy with `npx --package renovate renovate-config-validator <file>`.
