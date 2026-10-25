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

Pass `-PsigningKey=…` and `-PsigningPassword=…` to sign what you publish.

With `rpm { enabled = true }`, `publish` also uploads each RPM to a YUM repository; see
[Packaging and deploying](packaging.md#rpm).

## Smoke-testing a published app

Any build can check a published app, for example a deployment pipeline before it rolls a release
out, with the same smoke test the app's own build runs:

```kotlin
// build.gradle.kts of the checking build
plugins {
    id("io.github.fiftieshousewife.app-smoke-test") version "1.0.0"
}
repositories { maven("https://repo.example.com/libs-release-local") }
dependencies { smokeTest("com.example:orders-service:1.4.2") }
```

`./gradlew appSmokeTest` downloads the archive built for the machine it runs on, starts it and
waits for its health check. Run it on each target you publish for.
