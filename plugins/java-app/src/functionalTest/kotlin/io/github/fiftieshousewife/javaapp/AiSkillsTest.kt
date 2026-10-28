package io.github.fiftieshousewife.javaapp

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AiSkillsTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `aiSkills installs the skills and hooks for Copilot, Devin and Claude Code in the project`() {
        val project = javaService(tmp)
        project.file("$SKILLS/our-own/SKILL.md", "ours")
        project.build("aiSkills")
        val root = project.project
        assertAll(
            { assertTrue(root.resolve("$SKILLS/embedded-runtime-jlink-modules/SKILL.md").isFile) },
            { assertTrue(root.resolve(".github/hooks/embedded-runtime-check-gradle-build.sh").canExecute()) },
            {
                assertTrue(
                    root.resolve(
                        ".github/hooks/embedded-runtime-check-gradle-build.json",
                    ).readText().contains("postToolUse"),
                )
            },
            { assertTrue(root.resolve(".devin/hooks.v1.json").readText().contains("PostToolUse")) },
            {
                assertTrue(
                    root.resolve(".claude/settings.json").readText()
                        .contains("embedded-runtime@embedded-runtime-gradle-plugins"),
                )
            },
            { assertEquals("ours", root.resolve("$SKILLS/our-own/SKILL.md").readText().trim()) },
        )
    }

    @Test
    fun `assemble installs them, and a second run writes nothing`() {
        val project = javaService(tmp)
        project.build("assemble")
        val again = project.build("aiSkills")
        assertAll(
            { assertTrue(project.project.resolve("$SKILLS/embedded-runtime-adopt/SKILL.md").isFile) },
            { assertFalse(again.output.contains("Wrote"), again.output) },
        )
    }

    @Test
    fun `a build that turns them off writes nothing`() {
        val project = javaService(tmp)
        project.build("assemble", "-PjavaApp.aiSkills=false")
        assertFalse(project.project.resolve(".agents").exists())
    }

    private companion object {
        const val SKILLS = ".agents/skills"
    }
}
