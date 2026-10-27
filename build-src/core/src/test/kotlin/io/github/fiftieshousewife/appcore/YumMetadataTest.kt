package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class YumMetadataTest {
    @TempDir
    lateinit var dir: File

    private fun repository(vararg entries: YumRepositoryFixture.Entry): File {
        val rpm = dir.resolve("any.rpm").apply { writeText("rpm") }
        return YumRepositoryFixture.write(dir.resolve("repo"), entries.map { it.copy(rpm = rpm) })
    }

    private fun open(file: File) = Files.newInputStream(file.toPath())

    private fun entry(name: String, version: String, release: String, arch: String) =
        YumRepositoryFixture.Entry(File("x"), name, version, release, arch)

    private fun packages(repo: File, name: String): List<YumPackage> {
        val primary = open(repo.resolve(YumMetadata.REPOMD)).use(YumMetadata::primaryLocation)
        return open(repo.resolve(primary)).use { YumMetadata.packages(it, primary, name) }
    }

    @Test
    fun `repomd names the primary index, not the other indexes`() {
        val repo = repository(entry("jdk-21", "21.0.5", "1", "x86_64"))
        assertEquals(
            "repodata/abc123-primary.xml.gz",
            open(repo.resolve(YumMetadata.REPOMD)).use(YumMetadata::primaryLocation),
        )
    }

    @Test
    fun `the primary index gives each package's version, CPU, location and checksum`() {
        val repo = repository(entry("jdk-21", "21.0.5", "9", "x86_64"), entry("jdk-17", "17.0.13", "1", "x86_64"))
        val jdk = packages(repo, "jdk-21").single()
        assertAll(
            { assertEquals("jdk-21-21.0.5-9.x86_64", jdk.toString()) },
            { assertEquals("Packages/j/any.rpm", jdk.location) },
            { assertEquals("any.rpm", jdk.fileName) },
            { assertEquals("sha256", jdk.checksumType) },
            { assertEquals(YumRepositoryFixture.sha256(dir.resolve("any.rpm")), jdk.checksum) },
        )
    }

    @Test
    fun `the version of an rpm entry inside a package is not taken for the package's own`() {
        val jdk = packages(repository(entry("jdk-21", "21.0.5", "1", "x86_64")), "jdk-21").single()
        assertEquals("21.0.5", jdk.version)
    }

    @Test
    fun `the package for the pinned version and CPU is chosen, at its newest release`() {
        val all = packages(
            repository(
                entry("jdk-21", "21.0.5", "2", "x86_64"),
                entry("jdk-21", "21.0.5", "10", "x86_64"),
                entry("jdk-21", "21.0.5", "10", "aarch64"),
                entry("jdk-21", "21.0.4", "11", "x86_64"),
            ),
            "jdk-21",
        )
        assertAll(
            {
                assertEquals(
                    "jdk-21-21.0.5-10.x86_64",
                    YumMetadata.select(all, YumWanted("jdk-21", "21.0.5", "x86_64"), "r").toString(),
                )
            },
            {
                assertEquals(
                    "jdk-21-21.0.5-10.aarch64",
                    YumMetadata.select(all, YumWanted("jdk-21", "21.0.5", "aarch64"), "r").toString(),
                )
            },
        )
    }

    @Test
    fun `a version or CPU the repository lacks fails, listing what it has`() {
        val all = packages(repository(entry("jdk-21", "21.0.4", "1", "x86_64")), "jdk-21")
        val missing =
            assertThrows(GradleException::class.java) {
                YumMetadata.select(all, YumWanted("jdk-21", "21.0.5", "x86_64"), "yum-local")
            }
        val none =
            assertThrows(GradleException::class.java) {
                YumMetadata.select(emptyList(), YumWanted("jdk-25", "25.0.1", "x86_64"), "yum-local")
            }
        assertAll(
            { assertEquals("yum-local has no jdk-21 21.0.5 for x86_64. It has: 21.0.4-1.x86_64", missing.message) },
            { assertTrue(none.message!!.endsWith("It has no package named jdk-25 at all."), none.message) },
        )
    }
}
