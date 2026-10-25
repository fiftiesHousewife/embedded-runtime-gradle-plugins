// The top-level build owns no production code: it aggregates the plugin build in `build-src` so
// that `./gradlew build`, `check` and `publish` at the repository root do the expected thing.
plugins {
    base
}

description = "Gradle plugins that package Java and Node.js apps with their runtime embedded"

val pluginBuild = gradle.includedBuild("build-src")
val modules = listOf(":app-core", ":java-app", ":node-app")

tasks.named("assemble") { dependsOn(modules.map { pluginBuild.task("$it:assemble") }) }
tasks.named("check") { dependsOn(modules.map { pluginBuild.task("$it:check") }) }
tasks.named("clean") { dependsOn(modules.map { pluginBuild.task("$it:clean") }) }

// Release entry points, forwarded so the repository root is the only directory anyone needs:
//   ./gradlew publish        -PreleaseVersion=1.2.0   → Maven Central staging / the corporate repository
//   ./gradlew publishPlugins -PreleaseVersion=1.2.0   → the Gradle Plugin Portal (app-core must be on
//                                                       Maven Central first: the plugins depend on it)
listOf("publish", "publishToMavenLocal").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        description = "Runs $name in every module of the build-src plugin build"
        dependsOn(modules.map { pluginBuild.task("$it:$name") })
    }
}
tasks.register("publishPlugins") {
    group = "publishing"
    description = "Publishes java-app and node-app to the Gradle Plugin Portal"
    dependsOn(listOf(":java-app", ":node-app").map { pluginBuild.task("$it:publishPlugins") })
}
