package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.checks
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NodeAppPluginTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `builds, tests and smoke-tests a Node app with the pinned runtime`() {
        val project = webApp()
        val result = project.build("build")
        val archive = project.project.resolve("build/distributions/web-3.1.0-${TestProject.hostTarget()}.tar.gz")
        val entries = TestProject.run("tar", "-tzf", archive.absolutePath).lines()
        val runtime = project.project.resolve(
            "build/distributions/web-runtime-3.1.0-${TestProject.hostTarget()}.tar.gz",
        )
        val runtimeEntries = TestProject.run("tar", "-tzf", runtime.absolutePath).lines()
        val outcomes = listOf(":npmTest", ":appSmokeTest").map { task ->
            { assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome) }
        }
        val present = LAYOUT.map { path ->
            { assertTrue(entries.contains("web-3.1.0-${TestProject.hostTarget()}/$path"), "missing $path") }
        }
        val testsLeftOut = { assertFalse(entries.any { it.contains("server.test.mjs") }, "tests must not be packaged") }
        val runtimeApart = { assertTrue("runtime/bin/node" in runtimeEntries, runtimeEntries.joinToString("\n")) }
        assertAll(checks(outcomes + present + testsLeftOut + runtimeApart))
    }

    @Test
    fun `trusted certificates and TLS settings reach the launcher`() {
        val project = webApp()
        project.file("certs/corporate-root.pem", certificate())
        project.file(
            "build.gradle.kts",
            project.project.resolve("build.gradle.kts").readText() +
                """
                nodeApp.security {
                    trustedCertificates.from("certs")
                    tlsMinVersion = "1.3"
                }
                """.trimIndent(),
        )
        project.build("installDist", "appSmokeTest")
        val home = project.project.resolve("build/install/web")
        val launcher = home.resolve("bin/web").readText()
        assertAll(
            { assertTrue(home.resolve("security/trusted-cas.pem").readText().startsWith(PEM_HEADER)) },
            { assertTrue(launcher.contains("export NODE_EXTRA_CA_CERTS"), launcher) },
            { assertTrue(launcher.contains("'--tls-min-v1.3'"), launcher) },
        )
    }

    @Test
    fun `the runtime policy's certificates are trusted and its TLS floor is enforced`() {
        val project = webApp()
        val policy = tmp.resolve("policy").apply { resolve("certificates").mkdirs() }
        policy.resolve("certificates/acme-root.pem").writeText(certificate())
        policy.resolve("runtime-policy.properties").writeText("id=acme-node-runtime\nversion=3\ntlsMinVersion=1.3\n")
        val withPolicy = "-PnodeApp.securityPolicy=${policy.absolutePath}"
        val refused = project.buildAndFail("appArchive", withPolicy).output
        val buildScript = project.project.resolve("build.gradle.kts")
        buildScript.appendText("nodeApp.security.tlsMinVersion = \"1.3\"\n")
        project.build("appArchive", "installDist", "appSmokeTest", withPolicy)
        val home = project.project.resolve("build/install/web")
        assertAll(
            { assertTrue(refused.contains("breaks runtime policy acme-node-runtime 3"), refused) },
            { assertTrue(refused.contains("accept TLS below 1.3"), refused) },
            { assertTrue(home.resolve("security/trusted-cas.pem").readText().startsWith(PEM_HEADER)) },
        )
    }

    /** A throwaway self-signed CA, made with the JDK's keytool. */
    @Test
    fun `checkRuntimeSupport fails for a Node release past its end of life`() {
        val project = webApp()
        val major = NodeFixture.nodeVersion.substringBefore('.')
        project.file(
            "eol/products/nodejs",
            """{"result": {"releases": [{"name": "$major", "isEol": true, "eolFrom": "2026-04-30", "latest": null},
               {"name": "99", "isEol": false, "eolFrom": null, "latest": {"name": "99.0.0"}}]}}""",
        )
        val url = "-PnodeApp.runtimeSupportUrl=${project.project.resolve("eol/products").toURI()}"
        val failed = project.buildAndFail("checkRuntimeSupport", url)
        val allowed = project.build("checkRuntimeSupport", url, "-PnodeApp.allowEndOfLife=true")
        assertAll(
            { assertTrue(failed.output.contains("nodejs $major reached end of life on 2026-04-30"), failed.output) },
            { assertTrue(failed.output.contains("move to a supported release (99)"), failed.output) },
            { assertTrue(allowed.output.contains("Warning: nodejs $major reached end of life"), allowed.output) },
        )
    }

    private fun certificate(): String {
        val store = tmp.resolve("ca.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        TestProject.run(
            keytool, "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-dname", "CN=Test CA",
            "-ext", "bc:c", "-keystore", store.absolutePath, "-storepass", "changeit", "-validity", "1",
        )
        return TestProject.run(
            keytool, "-exportcert", "-rfc", "-alias", "ca", "-keystore", store.absolutePath, "-storepass", "changeit",
        )
    }

    @Test
    fun `npm logs in to the npm repository beside a borrowed one, with its credentials`() {
        val server = "https://repo.example.com/artifactory"
        val project = webApp()
        project.file("settings.gradle.kts", settingsBorrowing(server))
        project.gradleProperties(
            *NodeFixture.properties(project),
            "binaryRepo.repository" to "artifactory",
            "artifactory_user" to "dev",
            "artifactory_token" to "s3cret",
        )
        project.build("npmrc")
        val npmrc = project.project.resolve("build/app-work/npm/npmrc").readText()
        val auth = java.util.Base64.getEncoder().encodeToString("dev:s3cret".toByteArray())
        assertAll(
            { assertTrue(npmrc.contains("registry=$server/api/npm/npm-virtual/"), npmrc) },
            { assertTrue(npmrc.contains("//repo.example.com/artifactory/api/npm/npm-virtual/:_auth=$auth"), npmrc) },
        )
    }

    /** Settings declaring the build's own repository, logging in under its own property names. */
    private fun settingsBorrowing(server: String) =
        """
        plugins { id("${NodeAppSettingsPlugin.ID}") }
        rootProject.name = "web"
        dependencyResolutionManagement {
            repositories {
                maven {
                    name = "artifactory"
                    url = uri("$server/maven-virtual")
                    credentials {
                        username = providers.gradleProperty("artifactory_user").get()
                        password = providers.gradleProperty("artifactory_token").get()
                    }
                }
            }
        }
        """

    private fun webApp() = TestProject(tmp).apply {
        NodeFixture.publish(this)
        gradleProperties(*NodeFixture.properties(this))
        settings(NodeAppSettingsPlugin.ID, "web")
        listOf(
            "build.gradle.kts.template",
            "package.json",
            "package-lock.json",
            "src/server.mjs",
            "test/server.test.mjs",
        )
            .forEach { file(it.removeSuffix(".template"), fixture("web/$it")) }
    }

    private fun fixture(path: String) = NodeAppPluginTest::class.java.getResource("/fixtures/$path")!!.readText()

    private companion object {
        const val PEM_HEADER = "-----BEGIN CERTIFICATE-----"
        val LAYOUT = listOf("bin/web", "app/server.mjs", "app/package.json")
    }
}
