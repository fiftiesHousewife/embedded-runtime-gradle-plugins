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
val aiCopies =
    mapOf(
        "agentSkills" to ("ai/skills" to ".agents/skills"),
        "claudeSkills" to ("ai/skills" to "ai/claude/skills"),
        "copilotPrompts" to ("ai/copilot/prompts" to ".github/prompts"),
    )
val syncs =
    aiCopies.map { (name, paths) ->
        tasks.register<Sync>("sync${name.replaceFirstChar(Char::uppercase)}") {
            from(paths.first)
            into(paths.second)
        }
    }
val copilotInstructions =
    tasks.register<Copy>("syncCopilotInstructions") {
        from("ai/copilot/copilot-instructions.md")
        into(".github")
    }
tasks.register("syncAiSkills") {
    group = "build setup"
    description = "Copies the AI assistant files in ai/ to where each tool reads them"
    dependsOn(syncs, copilotInstructions)
}
val verifyAiSkills =
    tasks.register("verifyAiSkills") {
        group = "verification"
        description = "Fails when a copy of the AI assistant files in ai/ is out of date"
        val root = layout.projectDirectory
        val pairs = aiCopies.values.toList() + ("ai/copilot/copilot-instructions.md" to ".github/copilot-instructions.md")
        inputs.files(pairs.flatMap { listOf(root.file(it.first), root.file(it.second)) })
        doLast {
            // Local, so the task holds no reference to the build script (configuration cache).
            fun sameContent(source: File, copy: File): Boolean {
                if (source.isFile) return copy.isFile && source.readBytes().contentEquals(copy.readBytes())
                val files = { dir: File -> dir.walkTopDown().filter(File::isFile).associateBy { it.relativeTo(dir).invariantSeparatorsPath } }
                val a = files(source)
                val b = if (copy.isDirectory) files(copy) else emptyMap()
                return a.keys == b.keys && a.all { (path, file) -> file.readBytes().contentEquals(b.getValue(path).readBytes()) }
            }
            val stale = pairs.filter { (source, copy) -> !sameContent(root.file(source).asFile, root.file(copy).asFile) }
            if (stale.isNotEmpty()) {
                throw GradleException("Out of date: ${stale.joinToString { it.second }}. Run ./gradlew syncAiSkills")
            }
        }
    }
tasks.named("check") { dependsOn(verifyAiSkills) }
