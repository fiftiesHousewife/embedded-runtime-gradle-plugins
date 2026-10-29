# Renovate presets

The plugins pin every runtime they embed, and the container base image, so upgrading the plugin is
how those move: Renovate's own Gradle support already opens that pull request. The presets in
[`renovate/`](../renovate) cover what a project pins itself.

| Preset | Updates | From |
|---|---|---|
| [`temurin`](../renovate/temurin.json) | `javaApp.jdk.<feature>=…` in `gradle.properties` | Adoptium's releases on GitHub |
| [`nodejs`](../renovate/nodejs.json) | `nodeApp.node.version=…` in `gradle.properties` | nodejs.org |
| [`ubi`](../renovate/ubi.json) | `BASE_IMAGE=<image>:<tag>@sha256:<digest>` overrides of the Dockerfile's base image | The image's registry |

## The runtime presets

```json
{
  "extends": [
    "config:recommended",
    "github>fiftiesHousewife/embedded-runtime-gradle-plugins//renovate/temurin",
    "github>fiftiesHousewife/embedded-runtime-gradle-plugins//renovate/nodejs"
  ]
}
```

- **`temurin`** waits seven days before proposing a release, since Adoptium uploads one target at a
  time. For other distributions, rely on [`checkRuntimeSupport`](runtime-updates.md#checkruntimesupport).
- **Both stay within the release line.** Moving from Java 21 to 25, or Node.js 24 to 26, is a
  decision, not an update, so neither proposes it.
- **Self-hosted Renovate** needs a read-only GitHub token (`RENOVATE_GITHUB_COM_TOKEN`) to look up
  Adoptium's releases; the hosted Renovate app has one already.

## The base image preset

`ubi` is a reference example for whoever runs Renovate for your organisation, to copy into the
central configuration and adapt, rather than a preset to extend as it is. The Dockerfile's own base
image comes from the plugin release; this is for teams that pass their own with
`--build-arg BASE_IMAGE=…`, such as a registry mirror. UBI micro is not a drop-in `BASE_IMAGE`: the
Java runtime needs zlib, which micro lacks, so it takes a Dockerfile of its own
([On UBI micro](container-testing.md#on-ubi-micro)) with two pins, `BASE_IMAGE` and `BUILD_IMAGE`,
to keep current together.

- **Pin a rebuild, not the floating tag.** Red Hat tags each rebuild of UBI 9 minimal and micro
  `9.<minor>-<build>` and moves `9.<minor>`, `latest` and a bare build number along with it. Pin
  `ubi9/ubi-minimal:9.8-1793050346@sha256:…`, as the plugin does: the tag says which rebuild, the
  digest makes it exact.
- **One pull request moves tag and digest.** A newer rebuild of the same minor, which is how Red
  Hat ships security fixes, arrives as a patch; a newer minor as a minor. Floating tags are never
  proposed.
- **Where it looks:** GitHub Actions workflows, `.gitlab-ci.yml`, `Jenkinsfile`, Makefiles, shell
  scripts and `gradle.properties`. Add your own files to `managerFilePatterns`.
- **It leaves the plugin's Dockerfile alone.** Renovate's own Dockerfile support finds the
  `ARG BASE_IMAGE` default in the plugin's managed block, which each plugin release rewrites, so
  the preset turns that off for UBI rather than open pull requests the next build undoes.
- **Mirrors** need the `hostRules` credentials Renovate already uses for that registry.

Check an adapted copy with Renovate's validator, on Node.js 24.11 or later (older Node.js silently
gets an old Renovate that rejects current presets):

```bash
npx --package renovate renovate-config-validator renovate.json
```
