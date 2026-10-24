// The top-level build owns no production code: it aggregates the plugin build in `build-src` so
// that `./gradlew build`, `check` and `publish` at the repository root do the expected thing.
plugins {
    base
}

description = "Self-contained Java and Node.js bundles with pinned runtimes, built the same way locally, in CI and for servers and containers"

val pluginBuild = gradle.includedBuild("build-src")

tasks.named("assemble") { dependsOn(pluginBuild.task(":assemble")) }
tasks.named("check") { dependsOn(pluginBuild.task(":check")) }
tasks.named("clean") { dependsOn(pluginBuild.task(":clean")) }

// Release entry points, forwarded so the repository root is the only directory anyone needs:
//   ./gradlew publish        -PreleaseVersion=1.2.0   → the corporate Gradle plugin repository
//   ./gradlew publishPlugins -PreleaseVersion=1.2.0   → the Gradle Plugin Portal
listOf("publish", "publishToMavenLocal", "publishPlugins").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        description = "Runs :$name in the build-src plugin build"
        dependsOn(pluginBuild.task(":$name"))
    }
}
