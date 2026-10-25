package io.github.fiftieshousewife.javaapp

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Choosing the runtime's modules: jdeps by default, and finding what jdeps cannot see. */
class JavaAppModulesTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `without jlinkModules the runtime gets what jdeps finds`() {
        val project = javaService(tmp, JavaServiceOptions(javaApp = ""))
        val result = project.build("build")
        val detected = project.project.resolve("build/app-work/jdeps/modules.txt").readText()
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
            { assertTrue(detected.contains("jdk.httpserver"), detected) },
        )
    }

    @Test
    fun `jlinkFindModules adds the modules a failing app turns out to need`() {
        val project = javaService(tmp, JavaServiceOptions(javaApp = """jlinkModules = listOf("java.base")"""))
        val failed = project.buildAndFail("build")
        val found = project.build("jlinkFindModules")
        assertAll(
            { assertTrue(failed.output.contains("./gradlew jlinkFindModules"), failed.output) },
            { assertTrue(found.output.contains("""additionalModules.addAll("jdk.httpserver")"""), found.output) },
        )
    }
}
