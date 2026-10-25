package io.github.fiftieshousewife.appcore.tasks

import groovy.json.JsonSlurper
import org.cyclonedx.model.Component
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class SbomTest {
    @TempDir
    lateinit var dir: File

    private val app = CycloneDx.App(CycloneDx.Coordinates("com.example", "svc", "2.0.0"), "linux-x64")

    @Test
    fun `lists the embedded runtime as a platform with an escaped purl`() {
        val runtime = CycloneDx.runtime("temurin", "21.0.12.1+1")
        assertAll(
            { assertEquals(Component.Type.PLATFORM, runtime.type) },
            { assertEquals("pkg:generic/temurin@21.0.12.1%2B1", runtime.purl) },
            { assertEquals(runtime.purl, runtime.bomRef) },
        )
    }

    @Test
    fun `hashes a Maven library when its file was resolved`() {
        val jar = File(dir, "lib-1.0.jar").also { Files.writeString(it.toPath(), "jar") }
        val library = CycloneDx.mavenLibrary("org.example:lib:1.0:lib-1.0.jar", mapOf(jar.name to jar))
        assertAll(
            { assertEquals("pkg:maven/org.example/lib@1.0", library.purl) },
            { assertEquals("org.example", library.group) },
            {
                assertEquals(
                    "0163f1eea7894350060624d315234d40c508ab251ba121714e234503045faadd",
                    library.hashes.single().value,
                )
            },
        )
    }

    @Test
    fun `reads npm packages from the hidden lockfile, scoped names included, links skipped`() {
        val lockfile =
            mapOf(
                "packages" to
                    mapOf(
                        "" to mapOf("name" to "web"),
                        "node_modules/@types/node" to mapOf("version" to "24.0.0"),
                        "node_modules/left-pad" to mapOf("version" to "1.3.0", "integrity" to "sha1-3q2+7w=="),
                        "node_modules/local" to mapOf("version" to "1.0.0", "link" to true),
                    ),
            )
        val packages = NpmLockfile.npmPackages(lockfile).associateBy { it.name }
        assertAll(
            { assertEquals(setOf("@types/node", "left-pad"), packages.keys) },
            { assertEquals("pkg:npm/%40types/node@24.0.0", packages.getValue("@types/node").purl) },
            { assertEquals("SHA-1", packages.getValue("left-pad").hashes.single().algorithm) },
        )
    }

    @Test
    fun `ignores an integrity value in an algorithm it does not know`() {
        assertNull(NpmLockfile.integrityHash("md5-AAAA"))
    }

    @Test
    fun `writes the same document for the same components, in a stable order`() {
        val components = listOf(CycloneDx.runtime("temurin", "21"), CycloneDx.mavenLibrary("a:b:1:b.jar", emptyMap()))
        val first = CycloneDx.json(app, components)
        val document = JsonSlurper().parseText(first) as Map<*, *>
        val refs = (document["components"] as List<*>).map { (it as Map<*, *>)["bom-ref"] }
        assertAll(
            { assertEquals(first, CycloneDx.json(app, components.reversed())) },
            { assertEquals("1.5", document["specVersion"]) },
            { assertEquals(refs.map { it.toString() }.sorted(), refs) },
        )
    }

    @Test
    fun `names an app without a Maven group with a generic purl`() {
        val ungrouped = CycloneDx.App(CycloneDx.Coordinates("", "svc", "1.0.0"), "linux-x64")
        val document = JsonSlurper().parseText(CycloneDx.json(ungrouped, emptyList())) as Map<*, *>
        val subject = (document["metadata"] as Map<*, *>)["component"] as Map<*, *>
        assertEquals("pkg:generic/svc@1.0.0?classifier=linux-x64", subject["purl"])
    }

    @Test
    fun `records no timestamp, so a rebuild is byte-identical`() {
        val document = JsonSlurper().parseText(CycloneDx.json(app, emptyList())) as Map<*, *>
        assertEquals(null, (document["metadata"] as Map<*, *>)["timestamp"])
    }
}
