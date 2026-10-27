// The top-level build owns no production code: it aggregates the plugin build in `build-src` so
// that `./gradlew build`, `check` and `publish` at the repository root do the expected thing.
plugins {
    base
    alias(libs.plugins.gradle.node)
}

description = "Gradle plugins that package Java and Node.js apps with their runtime embedded"

val pluginBuild = gradle.includedBuild("build-src")
val modules = listOf(":app-core", ":java-app", ":node-app")

tasks.named("assemble") { dependsOn(modules.map { pluginBuild.task("$it:assemble") }) }
tasks.named("check") { dependsOn(modules.map { pluginBuild.task("$it:check") } + pluginBuild.task(":check")) }
tasks.named("clean") { dependsOn(modules.map { pluginBuild.task("$it:clean") }) }

// Release entry points, forwarded so the repository root is the only directory anyone needs:
//   ./gradlew verifyMavenCentralBundle -PreleaseVersion=1.2.0   → stage the release and check it
//   ./gradlew publishToMavenCentral    -PreleaseVersion=1.2.0   → and release it, as one deployment
//   ./gradlew publishToMavenLocal      -PreleaseVersion=1.2.0   → ~/.m2, to try a release first
//   ./gradlew publish                  -PreleaseVersion=1.2.0   → the staging directory, and the
//                                                                  corporate repository when binaryRepo.url is set
//   ./gradlew publishPlugins           -PreleaseVersion=1.2.0   → the Gradle Plugin Portal
listOf("publish", "publishToMavenLocal").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        description = "Runs $name in every module of the build-src plugin build"
        dependsOn(modules.map { pluginBuild.task("$it:$name") })
    }
}
listOf("verifyMavenCentralBundle", "publishToMavenCentral").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        description = "Runs $name in the build-src plugin build: every module as one Maven Central deployment"
        dependsOn(pluginBuild.task(":$name"))
    }
}
tasks.register("publishPlugins") {
    group = "publishing"
    description = "Publishes java-app, node-app and app-smoke-test (in app-core) to the Gradle Plugin Portal"
    dependsOn(modules.map { pluginBuild.task("$it:publishPlugins") })
}

// AI assistant files: one source in ai/. The Claude Code plugin is installed from ai/claude alone, so
// it carries its own copies of the skills and the hook; check fails while a copy differs.
val aiCopies =
    mapOf(
        "ai/claude/skills" to listOf("ai/skills"),
        "ai/claude/hooks/scripts" to listOf("ai/hooks"),
    )
val syncs =
    aiCopies.map { (destination, sources) ->
        tasks.register<Sync>("sync" + destination.split('/', '.', '-').joinToString("") { it.replaceFirstChar(Char::uppercase) }) {
            sources.forEach { from(it) }
            into(destination)
        }
    }
tasks.register("syncAiSkills") {
    group = "build setup"
    description = "Copies the skills and hook in ai/ into the Claude Code plugin in ai/claude"
    dependsOn(syncs)
}
val verifyAiSkills =
    tasks.register("verifyAiSkills") {
        group = "verification"
        description = "Fails when a copy of the AI assistant files in ai/ is out of date"
        val root = layout.projectDirectory.asFile
        val dirs = aiCopies
        inputs.files(dirs.flatMap { (d, s) -> s + d }.map { root.resolve(it) })
        doLast {
            // Local functions, so the task holds no reference to the build script (configuration cache).
            fun tree(dir: File) = dir.walkTopDown().filter(File::isFile).associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }
            val staleDirs = dirs.filter { (destination, sources) ->
                sources.map { tree(root.resolve(it)) }.reduce { a, b -> a + b } != tree(root.resolve(destination))
            }.keys
            val stale = staleDirs
            if (stale.isNotEmpty()) throw GradleException("Out of date: ${stale.joinToString()}. Run ./gradlew syncAiSkills")
        }
    }
tasks.named("check") { dependsOn(verifyAiSkills) }

// Renovate presets in renovate/: Renovate's own validator, on the Node.js the catalogue pins.
// Renovate's version and lockfile are in tools/renovate-validator, where Renovate keeps them current.
node {
    version = libs.versions.node
    download = true
    distBaseUrl = null
    nodeProjectDir = layout.projectDirectory.dir("tools/renovate-validator")
    npmInstallCommand = "ci"
}
val validateRenovatePresets =
    tasks.register<com.github.gradle.node.npm.task.NpxTask>("validateRenovatePresets") {
        group = "verification"
        description = "Checks the Renovate presets in renovate/ with Renovate's config validator"
        dependsOn(tasks.named("npmInstall"))
        val presets = layout.projectDirectory.dir("renovate").asFileTree.matching { include("*.json") }
        inputs.files(presets)
        command = "renovate-config-validator"
        args = presets.elements.map { files -> files.map { it.asFile.absolutePath }.sorted() }
    }
tasks.named("check") { dependsOn(validateRenovatePresets) }
