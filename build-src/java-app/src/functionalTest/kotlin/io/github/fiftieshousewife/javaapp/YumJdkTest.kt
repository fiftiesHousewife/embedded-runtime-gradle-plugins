package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.YumRepositoryFixture
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The JDK installed on servers from a YUM repository, found by package, version and CPU in its metadata. */
class YumJdkTest {
    @TempDir
    lateinit var tmp: File

    private val target = JdkFixture.rpmTarget
    private val pin = JdkFixture.jdkVersion.substringBefore('+')

    /** A YUM repository holding the test JDK's RPM as `jdk-<feature>`, at [versions] for this machine's CPU. */
    private fun repository(vararg versions: String, checksum: String? = null): File {
        val rpm = JdkFixture.publishRpm(tmp.resolve("rpms"), group = "jdk")
        val entries = versions.map {
            YumRepositoryFixture.Entry(rpm, "jdk-${JdkFixture.javaFeature}", it, "1", target.arch.rpm)
        }
        return YumRepositoryFixture.write(tmp.resolve("yum-local"), entries) {
            checksum
                ?: YumRepositoryFixture.sha256(it.rpm)
        }
    }

    private fun project(repository: File) = javaService(
        tmp.resolve("app"),
        JavaServiceOptions(
            javaApp = "$FIXTURE_MODULES\ntargets(\"${target.id}\")",
            publishJdk = false,
            properties = listOf(
                JdkSources.YUM_REPO to repository.toURI().toString(),
                JdkSources.YUM_PACKAGE to "jdk-[feature]",
                // Gradle can't provision a compiler from an RPM: it compiles with the installed JDK.
                "org.gradle.java.installations.paths" to JdkFixture.home.absolutePath,
            ) + JdkFixture.hostJdkUnlessLinux(),
        ),
    )

    @Test
    fun `the JDK package for the pin and this machine's CPU is downloaded, checked, extracted and embedded`() {
        val project = project(repository("21.0.1", pin))
        val first = project.build("build")
        val again = project.build("build")
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, first.task(":downloadJdk${target.taskSuffix}")?.outcome) },
            {
                assertTrue(
                    first.output.contains("jdk-${JdkFixture.javaFeature}-$pin-1.${target.arch.rpm} from"),
                    first.output,
                )
            },
            { assertTrue(project.project.resolve("build/distributions/svc-2.0.0-${target.id}.tar.gz").isFile) },
            { if (JdkFixture.rpmTargetStarts) assertEquals(TaskOutcome.SUCCESS, first.task(":appSmokeTest")?.outcome) },
            // Nothing to fetch the second time: no network needed once the package is in place.
            { assertEquals(TaskOutcome.UP_TO_DATE, again.task(":downloadJdk${target.taskSuffix}")?.outcome) },
        )
    }

    @Test
    fun `a repository without the pinned version says which versions it has`() {
        val output = project(repository("21.0.1")).buildAndFail("build").output
        assertTrue(
            output.contains("has no jdk-${JdkFixture.javaFeature} $pin for ${target.arch.rpm}. It has: 21.0.1-1"),
            output,
        )
    }

    @Test
    fun `a package that doesn't match the repository's checksum is refused`() {
        // The metadata records another package's checksum, as if the file had been replaced underneath it.
        val output = project(repository(pin, checksum = "0".repeat(64))).buildAndFail("build").output
        assertTrue(output.contains("the download is corrupt or the package was replaced"), output)
    }

    /** The bug: on Windows, with the running JDK unusable, the Linux RPM was unpacked as the Windows JDK. */
    @Test
    fun `a target that isn't Linux never gets the Linux RPM, and the build says why the running JDK wasn't used`() {
        val host = Target.parse(TestProject.hostTarget())
        assumeTrue(host.os != Os.LINUX, "on Linux this machine's target is a Linux one")
        val project = javaService(
            tmp.resolve("app"),
            JavaServiceOptions(
                publishJdk = false,
                properties = listOf(
                    JdkSources.YUM_REPO to repository(pin).toURI().toString(),
                    JdkSources.YUM_PACKAGE to "jdk-[feature]",
                    JdkSources.REUSE_RUNNING to "false",
                ),
            ),
        )
        val output = project.buildAndFail("build").output
        assertAll(
            {
                assertTrue(output.contains("No JDK for ${host.id}: javaApp.jdk.yumRepo serves Linux JDKs only"), output)
            },
            { assertTrue(output.contains("reusing it is turned off (javaApp.jdk.reuseRunning=false)"), output) },
            { assertTrue(output.contains(JdkSources.pathProperty(JdkFixture.javaFeature, host)), output) },
        )
    }
}
