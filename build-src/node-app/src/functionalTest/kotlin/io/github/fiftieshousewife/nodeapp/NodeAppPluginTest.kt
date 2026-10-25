package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.TestProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NodeAppPluginTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `builds and smoke-tests a Node app with the pinned runtime`() {
        val project =
            TestProject(tmp).apply {
                NodeFixture.publish(this)
                gradleProperties(*NodeFixture.properties(this))
                settings(NodeAppSettingsPlugin.ID, "web")
                file(
                    "build.gradle.kts",
                    """
                plugins { id("io.github.fiftieshousewife.node-app") }
                group = "com.example.test"
                version = "3.1.0"
                nodeApp { entryPoint = "server.mjs" }
                """,
                )
                file("package.json", """{ "name": "web", "version": "3.1.0", "private": true, "scripts": { "test": "node --test" } }""")
                file(
                    "package-lock.json",
                    """
                    { "name": "web", "version": "3.1.0", "lockfileVersion": 3, "requires": true,
                      "packages": { "": { "name": "web", "version": "3.1.0" } } }
                    """,
                )
                file(
                    "src/server.mjs",
                    """
                import { createServer } from 'node:http';
                const port = Number(process.env.APP_PORT ?? 8080);
                if (process.argv.includes('--healthcheck')) {
                  const ok = await fetch(`http://127.0.0.1:${'$'}{port}/`).then(r => r.ok, () => false);
                  process.exit(ok ? 0 : 1);
                }
                createServer((_, res) => res.end('ok')).listen(port);
                """,
                )
                file("test/server.test.mjs", "import { test } from 'node:test'; test('runs', () => {});")
            }

        val result = project.build("build")

        assertEquals(TaskOutcome.SUCCESS, result.task(":npmTest")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":appSmokeTest")?.outcome)
        val archive = project.project.resolve("build/distributions/web-3.1.0-${TestProject.hostTarget()}.tar.gz")
        val entries = TestProject.run("tar", "-tzf", archive.absolutePath)
        listOf("web-3.1.0/bin/web", "web-3.1.0/runtime/bin/node", "web-3.1.0/app/server.mjs", "web-3.1.0/app/package.json")
            .forEach { assertTrue(entries.contains(it), "missing $it in\n$entries") }
        assertTrue(!entries.contains("test/server.test.mjs"), "tests must not be packaged")
    }
}
