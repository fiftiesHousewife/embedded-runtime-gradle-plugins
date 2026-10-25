package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.RecordingRepository
import io.github.fiftieshousewife.appcore.RpmEntry
import io.github.fiftieshousewife.appcore.RpmFile
import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The other ways to ship an app: a zip archive, and an RPM installed into a YUM repository. */
class JavaAppPackagingTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()
    private val localRepo =
        """publishing { repositories { maven { name = "local"; url = uri(layout.buildDirectory.dir("repo")) } } }"""

    @Test
    fun `a zip archive is smoke-tested and published like a tarball`() {
        val project = javaService(tmp, JavaServiceOptions("$FIXTURE_MODULES\narchiveFormat(\"zip\")", localRepo))
        val built = project.build("publish")
        val gmm = project.project.resolve("build/repo/com/example/test/svc/2.0.0/svc-2.0.0.module").readText()
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, built.task(":appSmokeTest")?.outcome) },
            { assertTrue(project.project.resolve("build/distributions/svc-2.0.0-$host.zip").isFile) },
            { assertTrue(gmm.contains(""""url": "svc-2.0.0-$host.zip""""), gmm) },
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

    @Test
    fun `publish uploads each RPM to the YUM repository with the publishing credentials`() {
        val requests = RecordingRepository().use { server ->
            val project = javaService(tmp, rpmService("repositoryUrl = uri(\"${server.url}/rpm-local\")"))
            project.build(
                "publish",
                "-PjavaApp.allowUntestedPublish=true",
                "-PbinaryRepoUsername=ci",
                "-PbinaryRepoPassword=secret",
            )
            server.requests
        }
        assertEquals(
            "PUT /rpm-local/svc-2.0.0-1.aarch64.rpm Basic Y2k6c2VjcmV0",
            requests.last { "aarch64" in it },
        )
    }

    private fun rpmService(rpm: String = "") = JavaServiceOptions(
        javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\")\nrpm {\n    enabled = true\n    $rpm\n}",
        build = localRepo,
    )

    private companion object {
        const val EXECUTABLE = 0b111_101_101
        const val CONFIG = 0b110_100_000
        const val PRIVATE_DIR = 0b111_101_000
        const val CONFIG_NOREPLACE = 1 or (1 shl 4)
    }
}
