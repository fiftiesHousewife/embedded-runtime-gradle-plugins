package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Builds that use a published app: app-smoke-test checks it before it is rolled out. */
class ConsumerPluginsTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()
    private val localRepo =
        """publishing { repositories { maven { name = "local"; url = uri(layout.buildDirectory.dir("repo")) } } }"""
    private val twoTargets =
        JavaServiceOptions(javaApp = "$FIXTURE_MODULES\ntargets(\"$host\", \"linux-aarch64\")", build = localRepo)

    /** A build of its own, applying [fixture] with [replacements] filled in. */
    private fun consumer(name: String, fixture: String, vararg replacements: Pair<String, String>) =
        TestProject(tmp.resolve(name)).apply {
            gradleProperties()
            file("settings.gradle.kts", """rootProject.name = "$name"""")
            file("build.gradle.kts", replacements.fold(fixture(fixture)) { text, (from, to) -> text.replace(from, to) })
        }

    private fun published(): String {
        val producer = javaService(tmp.resolve("producer"), twoTargets)
        producer.build("publish", "-PjavaApp.allowUntestedPublish=true")
        return producer.project.resolve("build/repo").toURI().toString()
    }

    @Test
    fun `a consuming build smoke-tests the published archive for its own machine`() {
        val result = consumer("deploy-check", "consumer/build.gradle.kts.template", "@REPO@" to published())
            .build("appSmokeTest")
        assertTrue(result.output.contains("Smoke test passed: svc-2.0.0-$host.tar.gz"), result.output)
    }

    @Test
    fun `a pipeline that requires the runtime policy refuses an archive built without it`() {
        val policy = File(ConsumerPluginsTest::class.java.getResource("/fixtures/policy")!!.toURI())
        val refused = consumer("deploy-check", "consumer/build.gradle.kts.template", "@REPO@" to published())
            .buildAndFail("appSmokeTest", "-PappSmokeTest.securityPolicy=${policy.absolutePath}")
        assertTrue(
            refused.output.contains("breaks runtime policy acme-java-runtime 7") &&
                refused.output.contains("does not lock runtime options"),
            refused.output,
        )
    }
}
