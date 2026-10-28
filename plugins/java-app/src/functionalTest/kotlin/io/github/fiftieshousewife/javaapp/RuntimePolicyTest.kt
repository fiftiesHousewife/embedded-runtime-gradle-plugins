package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.Properties

/** An organisation's runtime policy, set per machine, applied over every project and enforced. */
class RuntimePolicyTest {
    @TempDir
    lateinit var tmp: File

    private val policy = File(RuntimePolicyTest::class.java.getResource("/fixtures/policy")!!.toURI())
    private val policyProperty = "-PjavaApp.securityPolicy=${policy.absolutePath}"

    @Test
    fun `no archive is built from a project that falls short of the policy`() {
        val failed = javaService(tmp).buildAndFail("appArchive", policyProperty)
        assertTrue(
            failed.output.contains("breaks runtime policy acme-java-runtime 7") &&
                failed.output.contains("does not lock runtime options"),
            failed.output,
        )
    }

    @Test
    fun `the policy has the last word over the project's own security settings`() {
        val weakening =
            "$FIXTURE_MODULES\nsecurity { properties.put(\"networkaddress.cache.ttl\", \"-1\") }"
        val project = javaService(
            tmp,
            JavaServiceOptions(javaApp = weakening, properties = listOf("javaApp.runtimeOptions" to "locked")),
        )
        project.build("appArchive", policyProperty)
        val runtime = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/runtime")
        val effective = Properties().apply {
            Files.newInputStream(runtime.resolve("conf/security/java.security").toPath()).use(::load)
        }
        val record = runtime.resolve("conf/security/java-app-customisations.txt").readText()
        assertAll(
            { assertTrue(effective.getProperty("networkaddress.cache.ttl") == "30", effective.toString()) },
            { assertTrue(effective.getProperty("security.overridePropertiesFile") == "false") },
            { assertTrue(record.contains("runtime policy acme-java-runtime 7, sha256 "), record) },
        )
    }
}
