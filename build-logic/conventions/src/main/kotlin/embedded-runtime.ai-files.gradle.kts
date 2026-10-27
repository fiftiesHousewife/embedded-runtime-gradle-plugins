import io.github.fiftieshousewife.buildlogic.VerifyCopies

// The Claude Code plugin installs from ai/claude alone, so it carries copies of the shared skills and hook.
plugins {
    base
}

val aiCopies = mapOf("ai/claude/skills" to listOf("ai/skills"), "ai/claude/hooks/scripts" to listOf("ai/hooks"))

val syncs =
    aiCopies.map { (copy, sources) ->
        tasks.register<Sync>("sync" + copy.split('/', '-').joinToString("") { it.replaceFirstChar(Char::uppercase) }) {
            sources.forEach { from(it) }
            into(copy)
        }
    }

tasks.register("syncAiSkills") {
    group = "build setup"
    description = "Copies the skills and hook in ai/ into the Claude Code plugin in ai/claude"
    dependsOn(syncs)
}

val verifyAiSkills =
    tasks.register<VerifyCopies>("verifyAiSkills") {
        group = "verification"
        description = "Fails when a copy of the AI assistant files in ai/ is out of date"
        root = layout.projectDirectory
        copies = aiCopies
        inputs.files(aiCopies.flatMap { (copy, sources) -> sources + copy })
    }

tasks.named("check") { dependsOn(verifyAiSkills) }
