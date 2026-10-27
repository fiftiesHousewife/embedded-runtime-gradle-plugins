package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

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
            disabledTlsAlgorithms.addAll("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384")
            lockProperties = true
        }
        """

    @Test
    fun `approved certificates, policy and disabled ciphers reach the running JVM`() {
        val project = securedService()
        val result = project.build("build")
        val runtime = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/runtime")
        val seen = probe(runtime)
        val trusted = checkNotNull(Regex("""trusted=(\d+)""").find(seen)) { seen }.groupValues[1].toInt()
        assertAll(
            { assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome) },
            { assertTrue(seen.contains("ttl=30"), seen) },
            { assertTrue(seen.contains("serialFilter=!*"), seen) },
            { assertTrue(seen.contains("override=false"), seen) },
            { assertTrue(seen.contains("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384") && seen.contains("SSLv3"), seen) },
            { assertTrue(seen.contains("CN=acme root CA"), seen) },
            { assertTrue(trusted > 50, seen) },
        )
    }

    @Test
    fun `the runtime records what was changed and carries the overlay`() {
        val project = securedService()
        project.build("appArchive")
        val runtime = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/runtime")
        val record = runtime.resolve("conf/security/java-app-customisations.txt").readText()
        assertAll(
            { assertTrue(record.contains("approved-acme-root"), record) },
            { assertTrue(record.contains("SHA-256 8D:0E:B1"), record) },
            { assertTrue(runtime.resolve("conf/acme/README.txt").isFile) },
        )
    }

    @Test
    fun `a certificate fetched by URI and pinned by its SHA-256 is trusted`() {
        val pem = fixture("certificates/acme-root.pem")
        val certificate = tmp.resolve("pki/acme-root.pem").apply { parentFile.mkdirs() }.apply { writeText(pem) }
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.readBytes()))
        val pinned = "$FIXTURE_MODULES\nsecurity { trustedCertificate(uri(\"${certificate.toURI()}\"), \"$sha256\") }"
        val project = javaService(tmp.resolve("project"), JavaServiceOptions(javaApp = pinned))
        project.build("appArchive")
        val runtime = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/runtime")
        val record = runtime.resolve("conf/security/java-app-customisations.txt").readText()
        assertTrue(record.contains("approved-acme-root") && record.contains("CN=acme root CA"), record)
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
        val failed = project.buildAndFail("appArchive")
        assertTrue(failed.output.contains("The runtime contains jdk.jdwp.agent"), failed.output)
    }

    @Test
    fun `an operator's jvm options file reaches the JVM unless runtime options are locked`() {
        val open = javaService(tmp.resolve("open"))
        open.build(Target.parse(TestProject.hostTarget()).installTaskName)
        val conf = tmp.resolve("conf").apply { mkdirs() }
        conf.resolve("jvm.options").writeText("-XX:+NoSuchOptionAnywhere\n")
        val fromFile = runLauncher(open, conf)

        val locked =
            javaService(
                tmp.resolve("locked"),
                JavaServiceOptions(javaApp = "$FIXTURE_MODULES\nruntimeOptions(\"locked\")"),
            )
        locked.build(Target.parse(TestProject.hostTarget()).installTaskName)
        val emptyConf = tmp.resolve("empty-conf").apply { mkdirs() }
        val refused = runLauncher(locked, emptyConf, "APP_JAVA_OPTS" to "-agentlib:jdwp=transport=dt_socket")
        assertAll(
            { assertTrue(fromFile.second.contains("NoSuchOptionAnywhere"), fromFile.second) },
            { assertEquals(78, refused.first, refused.second) },
            { assertTrue(refused.second.contains("APP_JAVA_OPTS is set"), refused.second) },
        )
    }

    private fun runLauncher(project: TestProject, confDir: File, vararg env: Pair<String, String>): Pair<Int, String> {
        val launcher = project.project.resolve("build/app/${TestProject.hostTarget()}/svc-2.0.0/bin/svc")
        val process = ProcessBuilder(launcher.absolutePath, "run").redirectErrorStream(true).apply {
            environment().keys.removeIf { it.endsWith("_OPTIONS") || it.endsWith("_OPTS") }
            environment()["APP_CONF_DIR"] = confDir.absolutePath
            environment()["APP_ROOT"] = tmp.resolve("root").absolutePath
            environment().putAll(env)
        }.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        return (if (finished) process.exitValue() else -1) to process.inputStream.bufferedReader().readText()
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
