package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
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
        val project = javaService(tmp, javaApp = "")

        val result = project.build("build")
        assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome)
        val detected = project.project.resolve("build/app-work/jdeps/modules.txt").readText()
        assertTrue(detected.contains("jdk.httpserver"), detected)
    }

    @Test
    fun `jlinkFindModules adds the modules a failing app turns out to need`() {
        // jdk.httpserver left out: the app fails at startup with NoClassDefFoundError.
        val project = javaService(tmp, javaApp = """jlinkModules = listOf("java.base")""")

        val failed = project.buildAndFail("build")
        assertTrue(failed.output.contains("./gradlew jlinkFindModules"), failed.output)

        val found = project.build("jlinkFindModules")
        assertTrue(found.output.contains("""additionalModules.addAll("jdk.httpserver")"""), found.output)
    }
}
