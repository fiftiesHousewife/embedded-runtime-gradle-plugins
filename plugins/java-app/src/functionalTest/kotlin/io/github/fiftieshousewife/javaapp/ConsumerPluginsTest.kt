package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `a pipeline that requires a signer refuses an archive with no Sigstore bundle`() {
        val refused = verifyingConsumer(published())
            .buildAndFail("appSmokeTest", "-PappSmokeTest.signerIdentity=^https://github\\.com/example/.*$")
        assertAll(
            { assertTrue(refused.output.contains("svc-2.0.0-$host.tar.gz has no Sigstore bundle"), refused.output) },
            { assertFalse(refused.output.contains("Smoke test passed"), refused.output) },
        )
    }

    @Test
    fun `a pipeline that requires a signer refuses an archive whose bundle signs something else`() {
        val repo = published()
        // A genuine bundle, but for another file (sigstore-java's own jar): the signature can't match.
        val beside = File(java.net.URI(repo)).resolve("com/example/test/svc/2.0.0/svc-2.0.0-$host.tar.gz.sigstore.json")
        sigstoreFixture("sigstore-java-2.3.0.jar.sigstore.json").copyTo(beside)
        val refused = verifyingConsumer(repo).buildAndFail(
            "appSmokeTest",
            "-PappSmokeTest.signerIdentity=^https://github\\.com/sigstore/sigstore-java/.*$",
            "-PappSmokeTest.sigstoreTrustedRoot=${sigstoreFixture("trusted_root.json").absolutePath}",
        )
        assertAll(
            { assertTrue(refused.output.contains("svc-2.0.0-$host.tar.gz is not signed by"), refused.output) },
            { assertFalse(refused.output.contains("Smoke test passed"), refused.output) },
        )
    }

    @Test
    fun `the signer and the policy can be set in the build script instead of as properties`() {
        val policy = File(ConsumerPluginsTest::class.java.getResource("/fixtures/policy")!!.toURI())
        val project = verifyingConsumer(published())
        project.file(
            "build.gradle.kts",
            project.project.resolve("build.gradle.kts").readText() +
                "\nappSmokeTest { signerIdentity = \"^https://github\\\\.com/example/.*$\" }\n",
        )
        val unsigned = project.buildAndFail("appSmokeTest").output
        project.file(
            "build.gradle.kts",
            project.project.resolve("build.gradle.kts").readText()
                .replace(
                    Regex("appSmokeTest \\{.*}"),
                    "appSmokeTest { securityPolicy = \"${policy.invariantSeparatorsPath}\" }",
                ),
        )
        val unlocked = project.buildAndFail("appSmokeTest").output
        assertAll(
            { assertTrue(unsigned.contains("svc-2.0.0-$host.tar.gz has no Sigstore bundle"), unsigned) },
            { assertTrue(unlocked.contains("breaks runtime policy acme-java-runtime 7"), unlocked) },
        )
    }

    /** A consumer that can also fetch sigstore-java, as a real one would from its binary repository. */
    private fun verifyingConsumer(repo: String) =
        consumer("deploy-check", "consumer/build.gradle.kts.template", "@REPO@" to repo).apply {
            val build = project.resolve("build.gradle.kts")
            file("build.gradle.kts", build.readText() + "\nrepositories { mavenCentral() }\n")
        }

    private fun sigstoreFixture(name: String) =
        File(ConsumerPluginsTest::class.java.getResource("/fixtures/sigstore/$name")!!.toURI())
}
