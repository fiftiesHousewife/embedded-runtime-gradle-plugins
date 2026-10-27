package io.github.fiftieshousewife.javaapp

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Choosing the runtime's modules: jdeps by default, shared sets, and finding what jdeps cannot see. */
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
        val project = javaService(
            tmp,
            JavaServiceOptions(javaApp = "jlinkModules = listOf(\"java.base\")\nsharedModuleSets = false"),
        )
        val failed = project.buildAndFail("build")
        val found = project.build("jlinkFindModules")
        assertAll(
            { assertTrue(failed.output.contains("./gradlew jlinkFindModules"), failed.output) },
            { assertTrue(found.output.contains("""additionalModules.addAll("jdk.httpserver")"""), found.output) },
        )
    }

    @Test
    fun `by default the runtime is the smallest shared set holding the app's modules`() {
        val project = javaService(tmp, JavaServiceOptions(javaApp = """additionalModules.add("java.sql")"""))
        project.build("assemble")
        val modules = linkedModules(project.project)
        assertAll(
            { assertTrue("java.sql" in modules, modules.toString()) },
            { assertTrue("java.net.http" in modules, modules.toString()) },
        )
    }

    @Test
    fun `without shared sets the runtime holds exactly the app's modules`() {
        val project = javaService(
            tmp,
            JavaServiceOptions(javaApp = "additionalModules.add(\"java.sql\")\nsharedModuleSets = false"),
        )
        project.build("assemble")
        val modules = linkedModules(project.project)
        assertAll(
            { assertTrue("java.sql" in modules, modules.toString()) },
            { assertFalse("java.net.http" in modules, modules.toString()) },
        )
    }

    private fun linkedModules(dir: File): List<String> = dir.resolve("build/app").walk()
        .first { it.name == "release" && it.parentFile.name == "runtime" }
        .readLines()
        .first { it.startsWith("MODULES=") }
        .removePrefix("MODULES=")
        .trim('"')
        .split(' ')
}
