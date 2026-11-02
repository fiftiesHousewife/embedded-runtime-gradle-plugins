package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.YumRepositoryFixture
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The libraries archive: third-party jars alone, the same bytes for every version while they are unchanged. */
class LibsArchiveTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()

    private fun project() =
        javaService(tmp, JavaServiceOptions(build = fixture("java-service/library.gradle.kts.template")))

    private fun archive(project: TestProject, kind: String, version: String = "2.0.0") =
        project.project.resolve("build/distributions/$kind-$version-$host.tar.gz")

    private fun entries(file: File) =
        TestProject.run("tar", "-tzf", file.absolutePath).lines().filter(String::isNotBlank)

    @Test
    fun `holds the libraries alone, the app its own jar alone, and the app starts from the three`() {
        val project = project()
        val result = project.build("appSmokeTest")
        val libs = entries(archive(project, "svc-libs"))
        val app = entries(archive(project, "svc"))
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
            { assertTrue(libs.all { it.startsWith("lib/") }, libs.joinToString("\n")) },
            { assertTrue("lib/library-1.jar" in libs, libs.joinToString("\n")) },
            { assertFalse(libs.any { "svc-2.0.0" in it }, libs.joinToString("\n")) },
            { assertTrue(app.any { it.endsWith("/lib/svc-2.0.0.jar") }, app.joinToString("\n")) },
            { assertFalse(app.any { "library-1" in it }, app.joinToString("\n")) },
        )
    }

    @Test
    fun `a rebuild and a new version of the app give a byte-identical libraries archive, and a new app archive`() {
        val project = project()
        project.build("clean", "appArchive")
        val libs = YumRepositoryFixture.sha256(archive(project, "svc-libs"))
        val app = YumRepositoryFixture.sha256(archive(project, "svc"))
        project.build("clean", "appArchive", "--rerun-tasks")
        val rebuilt = YumRepositoryFixture.sha256(archive(project, "svc-libs"))
        project.build("appArchive", "-PappVersion=2.0.1")
        assertAll(
            { assertEquals(libs, rebuilt) },
            { assertEquals(libs, YumRepositoryFixture.sha256(archive(project, "svc-libs", "2.0.1"))) },
            { assertTrue(app != YumRepositoryFixture.sha256(archive(project, "svc", "2.0.1"))) },
        )
    }

    @Test
    fun `an app with no libraries still has an archive, to publish and unpack alike`() {
        val project = javaService(tmp)
        project.build("appSmokeTest")
        assertEquals(listOf("lib/", "lib/${LIBS_MARKER}"), entries(archive(project, "svc-libs")))
    }

    private companion object {
        const val LIBS_MARKER = ".libs"
    }
}
