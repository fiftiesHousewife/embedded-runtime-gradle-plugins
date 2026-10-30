package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.RpmEntry
import io.github.fiftieshousewife.appcore.RpmFile
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The other ways to ship an app: a zip archive, and an RPM. */
class JavaAppPackagingTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()
    private val localRepo =
        """publishing { repositories { maven { name = "local"; url = uri(layout.buildDirectory.dir("repo")) } } }"""

    @Test
    fun `a project's own SBOM replaces the built-in one in the archive and the publication`() {
        val own = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":{\"tools\":[{\"name\":\"ours\"}]}}"
        val project =
            javaService(
                tmp,
                JavaServiceOptions(
                    "$FIXTURE_MODULES\nsbom = layout.projectDirectory.file(\"own-sbom.json\")",
                    localRepo,
                ),
            )
        project.file("own-sbom.json", own)
        val built = project.build("publish")
        val archived = project.project.resolve("build/app/$host/svc-2.0.0/META-INF/sbom.cdx.json").readText()
        val published = project.project.resolve(
            "build/repo/com/example/test/svc/2.0.0/svc-2.0.0-$host-cyclonedx.json",
        ).readText()
        assertAll(
            {
                assertTrue(
                    built.task(":appSbom${Target.parse(host).taskSuffix}")?.outcome in setOf(null, TaskOutcome.SKIPPED),
                )
            },
            { assertEquals(own, archived.trim()) },
            { assertEquals(own, published.trim()) },
        )
    }

    @Test
    fun `the RPM installs the app under opt, its config as noreplace and a service account`() {
        val project = javaService(tmp, rpmService())
        project.build("appRpm")
        val rpm = RpmFile(project.project.resolve("build/distributions/svc-2.0.0-1.aarch64.rpm"))
        assertAll(
            {
                assertEquals(
                    listOf("svc", "2.0.0", "1", "aarch64"),
                    listOf(rpm.name, rpm.version, rpm.release, rpm.arch),
                )
            },
            { assertEquals(RpmEntry(EXECUTABLE, 0, "root", "root"), rpm.entries["/opt/svc/bin/svc"]) },
            { assertEquals(RpmEntry(CONFIG, CONFIG_NOREPLACE, "root", "svc"), rpm.entries["/etc/svc/app.properties"]) },
            { assertEquals(RpmEntry(PRIVATE_DIR, 0, "svc", "svc"), rpm.entries["/var/lib/svc"]) },
            { assertTrue("/usr/lib/systemd/system/svc.service" in rpm.entries, rpm.entries.keys.toString()) },
            {
                assertTrue(
                    rpm.entries.keys.none {
                        it == "/opt" || it.endsWith("/systemd/system")
                    },
                    rpm.entries.keys.toString(),
                )
            },
            { assertTrue(rpm.preInstall.contains("useradd -r -g svc"), rpm.preInstall) },
            { assertTrue("shadow-utils" in rpm.requires, rpm.requires.toString()) },
        )
    }

    private fun rpmService() = JavaServiceOptions(
        javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\")\nrpm { enabled = true }",
        build = localRepo,
    )

    private companion object {
        const val EXECUTABLE = 0b111_101_101
        const val CONFIG = 0b110_100_000
        const val PRIVATE_DIR = 0b111_101_000
        const val CONFIG_NOREPLACE = 1 or (1 shl 4)
    }
}
