// Aggregates the plugins build, so check, publish and the release tasks run from the repository root.
plugins {
    base
    id("embedded-runtime.renovate-presets")
    id("embedded-runtime.ai-files")
}

description = "Gradle plugins that package Java and Node.js apps with their runtime embedded"

val pluginBuild = gradle.includedBuild("plugins")
val modules = listOf(":app-core", ":java-app", ":node-app")

tasks.named("assemble") { dependsOn(modules.map { pluginBuild.task("$it:assemble") }) }
tasks.named("check") { dependsOn(modules.map { pluginBuild.task("$it:check") } + pluginBuild.task(":check")) }
tasks.named("clean") { dependsOn(modules.map { pluginBuild.task("$it:clean") }) }

listOf("publish", "publishToMavenLocal", "publishPlugins").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        description = "Runs $name in every module of the plugins build"
        dependsOn(modules.map { pluginBuild.task("$it:$name") })
    }
}
listOf("verifyMavenCentralBundle", "publishToMavenCentral").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        description = "Runs $name in the plugins build: every module as one Maven Central deployment"
        dependsOn(pluginBuild.task(":$name"))
    }
}
