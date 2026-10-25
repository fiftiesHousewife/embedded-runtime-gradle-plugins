package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** An organisation's certificates, policy and hardening, applied to the runtime and seen by its JVM. */
class JavaAppSecurityTest {
    @TempDir
    lateinit var tmp: File

    private val security =
        """
        $FIXTURE_MODULES
        security {
            trustedCertificates.from("certs")
            propertiesFiles.from("security/policy.properties")
            disabledTlsAlgorithms.addAll("TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_256_GCM_SHA384")
            lockProperties = true
        }
        """

    @Test
    fun `approved certificates, policy and disabled ciphers reach the running JVM`() {
        val project = securedService()
        val result = project.build("build")
        val runtime = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/runtime")
        val seen = probe(runtime)
        val trusted = Regex("""trusted=(\d+)""").find(seen)!!.groupValues[1].toInt()
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
            { assertTrue(seen.contains("ttl=30"), seen) },
            { assertTrue(seen.contains("serialFilter=!*"), seen) },
            { assertTrue(seen.contains("override=false"), seen) },
            { assertTrue(seen.contains("TLS_RSA_WITH_AES_128_GCM_SHA256") && seen.contains("SSLv3"), seen) },
            { assertTrue(seen.contains("CN=acme root CA"), seen) },
            { assertTrue(trusted > 50, seen) },
        )
    }

    @Test
    fun `the runtime records what was changed and carries the overlay`() {
        val project = securedService()
        project.build("appTar")
        val runtime = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/runtime")
        val record = runtime.resolve("conf/security/java-app-customisations.txt").readText()
        assertAll(
            { assertTrue(record.contains("approved-acme-root"), record) },
            { assertTrue(record.contains("SHA-256 8D:0E:B1"), record) },
            { assertTrue(runtime.resolve("conf/acme/README.txt").isFile) },
        )
    }

    @Test
    fun `a forbidden module fails the build`() {
        val project =
            javaService(
                tmp,
                JavaServiceOptions(
                    javaApp = """jlinkModules = listOf("java.base", "jdk.httpserver", "jdk.jdwp.agent")""",
                ),
            )
        val failed = project.buildAndFail("appTar")
        assertTrue(failed.output.contains("The runtime contains jdk.jdwp.agent"), failed.output)
    }

    private fun securedService() = javaService(tmp, JavaServiceOptions(javaApp = security)).apply {
        file("certs/acme-root.pem", fixture("certificates/acme-root.pem"))
        file("security/policy.properties", "networkaddress.cache.ttl=30\njdk.serialFilter=!*")
        file("src/deploy/runtime/conf/acme/README.txt", "added by the overlay")
    }

    /** Runs the probe on [runtime]: what its JVM sees as security settings and trusted CAs. */
    private fun probe(runtime: File): String {
        val source = tmp.resolve("probe/Probe.java").apply { parentFile.mkdirs() }
        source.writeText(fixture("probe/Probe.java"))
        TestProject.run(
            JdkFixture.home.resolve("bin/javac").absolutePath,
            "-d",
            source.parentFile.absolutePath,
            source.absolutePath,
        )
        return TestProject.run(runtime.resolve("bin/java").absolutePath, "-cp", source.parentFile.absolutePath, "Probe")
    }
}
