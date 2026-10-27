# Publishing

`./gradlew publish` uploads every target's archive and SBOM to your Maven repository, under one
version, with the target as the classifier:

```
com/example/orders-service/1.4.2/orders-service-1.4.2-linux-x64.tar.gz
com/example/orders-service/1.4.2/orders-service-1.4.2-linux-x64-cyclonedx.json
com/example/orders-service/1.4.2/orders-service-1.4.2-windows-x64.tar.gz   # or .zip, with archiveFormat
…
```

With a binary repository configured, releases go to `libs-release-local` and `-SNAPSHOT` versions to
`libs-snapshot-local`. To publish under other coordinates:

```kotlin
publishing.publications.named<MavenPublication>("javaApp") {   // or "nodeApp"
    groupId = "com.acme.apps"
    artifactId = "orders"
}
```

## Signing

Two ways to sign what you publish, and they can be used together:

- **Sigstore, keyless, from CI.** Apply Sigstore's own Gradle plugin and publish from a CI job
  that has an identity token (GitHub Actions with `permissions: id-token: write`; GitLab and others
  work too). Every published file gets a `<file>.sigstore.json` bundle beside it: a signature by a
  short-lived certificate naming the workflow that built it, recorded in Sigstore's public
  transparency log. There is no key to store, rotate or leak.

  ```kotlin
  plugins {
      id("io.github.fiftieshousewife.java-app")
      id("dev.sigstore.sign") version "2.3.0"
  }
  ```

  Maven Central accepts these bundles alongside PGP signatures. On a developer machine the plugin
  opens a browser to sign in instead, so apply it only in CI if that matters (the
  [Java sample](../samples/java-service/build.gradle.kts) applies it with `-PsigstoreSign=true`).
- **PGP**, for sites that can't reach Sigstore: pass `-PsigningKey=…` and `-PsigningPassword=…`.

For SLSA build provenance (which repository, commit, workflow and runner built each archive), add
GitHub's attestation step after publishing; `gh attestation verify <archive> --repo <owner>/<repo>`
checks it:

```yaml
- uses: actions/attest-build-provenance@v4.2.2
  with:
    subject-path: build/distributions/*.tar.gz
```

With `rpm { enabled = true }`, `publish` also uploads each RPM to a YUM repository; see
[Packaging and deploying](packaging.md#rpm).

## Smoke-testing a published app

Any build can check a published app, for example a deployment pipeline before it rolls a release
out, with the same smoke test the app's own build runs:

```kotlin
// build.gradle.kts of the checking build
plugins {
    id("io.github.fiftieshousewife.app-smoke-test") version "0.0.4"
}
repositories { maven("https://repo.example.com/libs-release-local") }
dependencies { smokeTest("com.example:orders-service:1.4.2") }
```

`./gradlew appSmokeTest` downloads the archive built for the machine it runs on, starts it and
waits for its health check. Run it on each target you publish for.

### Only archives your release workflow signed

A checksum from the same server proves the download is intact, not who built it. With a signer
identity, `appSmokeTest` first fetches each archive's Sigstore bundle from beside it in the
repository and verifies it: a valid signature, by a certificate issued to that identity, recorded
in the transparency log. Anything else stops the pipeline before the archive is even unpacked.

```properties
# gradle.properties of the checking build, or -P on its command line
appSmokeTest.signerIdentity=^https://github\.com/acme/orders/\.github/workflows/release\.yml@refs/tags/v.*$
# the default
appSmokeTest.signerIssuer=https://token.actions.githubusercontent.com
```

The identity is a regular expression matched against the certificate's subject: for GitHub Actions,
`https://github.com/<owner>/<repo>/<workflow path>@<ref>`. Pin the workflow and the refs releases
come from, so a build from a branch or a fork can't pass. The verifier, sigstore-java, is downloaded
only when an identity is set, from the checking build's own repositories, so add Maven Central or
your binary repository's Maven proxy to them.

Verification needs Sigstore's trusted root, which sigstore-java fetches from Sigstore and keeps up to
date. A machine that can't reach it can be given a copy (`trusted_root.json` from
[sigstore/root-signing](https://github.com/sigstore/root-signing/tree/main/targets)):

```properties
appSmokeTest.sigstoreTrustedRoot=/etc/sigstore/trusted_root.json
```

CI in this repository does all of this for real on every push: the Java sample is signed with the
workflow's identity and given build provenance, then
[`samples/release-check`](../samples/release-check) verifies and starts it, and is shown to refuse
it when another identity is required.
