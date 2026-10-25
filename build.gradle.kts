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
//                                                       Maven Central first: the other plugins depend on it)
listOf("publish", "publishToMavenLocal").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        description = "Runs $name in every module of the build-src plugin build"
        dependsOn(modules.map { pluginBuild.task("$it:$name") })
    }
}
tasks.register("publishPlugins") {
    group = "publishing"
    description = "Publishes java-app, node-app and app-smoke-test (in app-core) to the Gradle Plugin Portal"
    dependsOn(modules.map { pluginBuild.task("$it:publishPlugins") })
}

// AI assistant files: one source in ai/, copied to the paths each tool requires (see ai/README.md).
// Each entry is a destination and what it holds; check fails while any copy differs.
val aiCopies =
    mapOf(
        ".agents/skills" to listOf("ai/skills"),
        "ai/claude/skills" to listOf("ai/skills"),
        "ai/claude/hooks/scripts" to listOf("ai/hooks"),
        ".github/hooks" to listOf("ai/hooks", "ai/copilot/hooks"),
    )
val aiFiles = mapOf(".github/copilot-instructions.md" to "ai/copilot/copilot-instructions.md", ".devin/hooks.v1.json" to "ai/devin/hooks.v1.json")
val syncs =
    aiCopies.map { (destination, sources) ->
        tasks.register<Sync>("sync" + destination.split('/', '.', '-').joinToString("") { it.replaceFirstChar(Char::uppercase) }) {
            sources.forEach { from(it) }
            into(destination)
        }
    } +
        aiFiles.map { (destination, source) ->
            tasks.register<Copy>("copy" + destination.split('/', '.', '-').joinToString("") { it.replaceFirstChar(Char::uppercase) }) {
                from(source)
                into(destination.substringBeforeLast('/'))
            }
        }
tasks.register("syncAiSkills") {
    group = "build setup"
    description = "Copies the AI assistant files in ai/ to where each tool reads them"
    dependsOn(syncs)
}
val verifyAiSkills =
    tasks.register("verifyAiSkills") {
        group = "verification"
        description = "Fails when a copy of the AI assistant files in ai/ is out of date"
        val root = layout.projectDirectory.asFile
        val dirs = aiCopies
        val files = aiFiles
        inputs.files(dirs.flatMap { (d, s) -> s + d }.map { root.resolve(it) } + files.flatMap { listOf(it.key, it.value) }.map { root.resolve(it) })
        doLast {
            // Local functions, so the task holds no reference to the build script (configuration cache).
            fun tree(dir: File) = dir.walkTopDown().filter(File::isFile).associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }
            val staleDirs = dirs.filter { (destination, sources) ->
                sources.map { tree(root.resolve(it)) }.reduce { a, b -> a + b } != tree(root.resolve(destination))
            }.keys
            val staleFiles = files.filter { (destination, source) ->
                !root.resolve(destination).isFile || !root.resolve(destination).readBytes().contentEquals(root.resolve(source).readBytes())
            }.keys
            val stale = staleDirs + staleFiles
            if (stale.isNotEmpty()) throw GradleException("Out of date: ${stale.joinToString()}. Run ./gradlew syncAiSkills")
        }
    }
tasks.named("check") { dependsOn(verifyAiSkills) }
