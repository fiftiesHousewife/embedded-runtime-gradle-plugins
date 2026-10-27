package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.javaapp.tasks.JdkTools
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Where a build's JDKs come from: installed directories, RPMs, shared trees, and settings in code. */
class JdkSourceTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()

    @Test
    fun `a JDK installed in a directory is used as it is, on every OS`() {
        val jdk = JdkFixture.home.absolutePath
        val path = JdkSources.pathProperty(JdkFixture.javaFeature, Target.parse(host)) to jdk
        val project = javaService(
            tmp,
            JavaServiceOptions(
                publishJdk = false,
                properties = listOf(
                    path,
                    "org.gradle.java.installations.paths" to jdk,
                ),
                runsOnWindows = true,
            ),
        )
        val result = project.build("build")
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
            { assertNull(result.task(":extractJdk${Target.parse(host).taskSuffix}")) },
        )
    }

    @Test
    fun `a JDK packaged as an RPM, named the organisation's way, is extracted and embedded`() {
        val target = JdkFixture.rpmTarget
        val repository = tmp.resolve("corporate-jdks")
        JdkFixture.publishRpm(repository, group = "temurin", target = target.id)
        val project = rpmProject(repository, "[organisation]/jdk[revision]-64_[classifier].rpm")
        val result = project.build("build")
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":extractJdk${target.taskSuffix}")?.outcome) },
            {
                if (JdkFixture.rpmTargetStarts) assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome)
            },
        )
    }

    @Test
    fun `an RPM named with the OS and RPM CPU, in a folder per OS, is found from one pattern`() {
        val target = JdkFixture.rpmTarget
        val repository = tmp.resolve("corporate-jdks")
        val (feature, update) =
            JdkFixture.jdkVersion.substringBefore('+').split('.').let { it[0] to it.getOrElse(2) { "0" } }
        val named = repository.resolve(
            "temurin/${target.os.id}/jdk${feature}_$update-64_${target.os.id}.${target.arch.rpm}.rpm",
        )
        named.parentFile.mkdirs()
        JdkFixture.publishRpm(tmp.resolve("rpm"), group = "x").copyTo(named)
        val project = rpmProject(repository, "[organisation]/[os]/jdk[revision]-64_[os].[rpmArch].rpm")
        assertEquals(TaskOutcome.SUCCESS, project.build("build").task(":extractJdk${target.taskSuffix}")?.outcome)
    }

    /** A build of [JdkFixture.rpmTarget] whose JDK is an RPM in [repository], named by [pattern]. */
    private fun rpmProject(repository: File, pattern: String) = javaService(
        tmp,
        JavaServiceOptions(
            javaApp = "$FIXTURE_MODULES\ntargets(\"${JdkFixture.rpmTarget.id}\")",
            publishJdk = false,
            properties = listOf(
                JdkSources.LOCAL_PATH to repository.absolutePath,
                JdkSources.LOCAL_PATTERN to pattern,
                JdkSources.REVISION_FORMAT to "[feature]_[update]",
                // Gradle can't provision a compiler from an RPM: it compiles with the installed JDK.
                "org.gradle.java.installations.paths" to JdkFixture.home.absolutePath,
            ) + JdkFixture.hostJdkUnlessLinux(),
        ),
    )

    @Test
    fun `the JDK source and pin set in settings code reach the projects that build the app`() {
        val shared = tmp.resolve("shared-jdks")
        val project = javaService(tmp, JavaServiceOptions(publishJdk = false))
        project.file(
            "settings.gradle.kts",
            """
            plugins { id("${JavaAppSettingsPlugin.ID}") }
            rootProject.name = "svc"
            embeddedRuntime {
                jdk {
                    localPath = "${shared.absolutePath.replace('\\', '/')}"
                    pin(${JdkFixture.javaFeature}, "${JdkFixture.jdkVersion}")
                }
            }
            """,
        )
        JdkFixture.publishCurated(shared)
        assertEquals(TaskOutcome.SUCCESS, project.build("build").task(":appSmokeTest")?.outcome)
    }

    @Test
    fun `JDK archives are read from a shared local directory`() {
        val shared = tmp.resolve("shared-jdks")
        val project =
            javaService(
                tmp,
                JavaServiceOptions(
                    publishJdk = false,
                    properties = listOf(JdkSources.LOCAL_PATH to shared.absolutePath),
                ),
            )
        JdkFixture.publishCurated(shared)
        assertEquals(TaskOutcome.SUCCESS, project.build("build").task(":appSmokeTest")?.outcome)
    }

    @Test
    fun `the compile toolchain's JDK is used for this machine when it is the pinned one`() {
        val home = JdkFixture.home
        val distribution = JdkDistribution.entries.firstOrNull { it.implementor == JdkTools.implementor(home) }
        // The pin as the running JDK's own distribution spells its version.
        val pin = listOfNotNull(JdkTools.javaVersion(home), JdkFixture.jdkVersion).firstOrNull { candidate ->
            distribution != null && runCatching { distribution.requireVersion(JdkVersion(candidate)) }.isSuccess &&
                JdkTools.isPinned(candidate, home)
        }
        assumeTrue(
            pin != null && home.resolve("jmods").isDirectory,
            "the running JDK is no distribution's pinnable JDK",
        )
        val project = javaService(
            tmp,
            JavaServiceOptions(
                properties = listOf(
                    JdkSources.DISTRIBUTION to checkNotNull(distribution).id,
                    JdkSources.VERSION_PREFIX + JdkFixture.javaFeature to checkNotNull(pin),
                    JdkSources.REUSE_TOOLCHAIN to "true",
                ),
            ),
        )
        val result = project.build("build")
        assertAll(
            { assertNull(result.task(":extractJdk${Target.parse(host).taskSuffix}"), "nothing to unpack") },
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
        )
    }
}
