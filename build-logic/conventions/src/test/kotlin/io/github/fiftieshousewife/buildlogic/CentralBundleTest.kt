package io.github.fiftieshousewife.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class CentralBundleTest {
    @TempDir
    lateinit var staging: File

    private val prefix = "Embedded Runtime: "

    /** One module as maven-publish lays it out, with checksums and, when [signed], signatures. */
    private fun stage(name: String = "Embedded Runtime: Java app plugin", signed: Boolean = true, pom: String? = null) {
        val dir = staging.resolve("io/github/fiftieshousewife/java-app/0.0.3").apply { mkdirs() }
        dir.resolve("java-app-0.0.3.pom").writeText(pom ?: pom(name))
        dir.resolve("java-app-0.0.3.jar").writeText("jar")
        dir.listFiles()!!.forEach { file ->
            listOfNotNull("md5", "sha1", "asc".takeIf { signed }).forEach { dir.resolve("${file.name}.$it").writeText(it) }
        }
        staging.resolve("io/github/fiftieshousewife/java-app/maven-metadata.xml").writeText("<metadata/>")
    }

    private fun pom(name: String, description: String = "Packages a Java app") =
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.github.fiftieshousewife</groupId>
          <artifactId>java-app</artifactId>
          <version>0.0.3</version>
          <name>$name</name>
          <description>$description</description>
          <url>https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins</url>
          <licenses><license><name>Apache-2.0</name></license></licenses>
          <developers><developer><name>Pippa Newbold</name></developer></developers>
          <scm><url>https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins</url></scm>
        </project>
        """.trimIndent()

    @Test
    fun `a complete, named and signed release is ready`() {
        stage()
        assertEquals(emptyList<String>(), CentralBundle.problems(staging, prefix, signed = true))
    }

    @Test
    fun `a POM named after its module alone, as 0_0_1 and 0_0_2 were, is refused`() {
        stage(name = "java-app")
        val problem = CentralBundle.problems(staging, prefix, signed = true).single()
        assertEquals("io/github/fiftieshousewife/java-app/0.0.3/java-app-0.0.3.pom is named 'java-app', not 'Embedded Runtime: …'", problem)
    }

    @Test
    fun `a POM without what Central requires is refused`() {
        stage(pom = pom("Embedded Runtime: Java app plugin", description = ""))
        assertEquals(
            listOf("io/github/fiftieshousewife/java-app/0.0.3/java-app-0.0.3.pom has no <description>"),
            CentralBundle.problems(staging, prefix, signed = true),
        )
    }

    @Test
    fun `an unsigned release is refused when a signing key is configured, and accepted when none is`() {
        stage(signed = false)
        val problems = CentralBundle.problems(staging, prefix, signed = true)
        assertTrue(problems.contains("io/github/fiftieshousewife/java-app/0.0.3/java-app-0.0.3.jar has no .asc"), problems.toString())
        assertEquals(emptyList<String>(), CentralBundle.problems(staging, prefix, signed = false))
    }

    @Test
    fun `an empty staging directory is refused`() {
        assertTrue(CentralBundle.problems(staging, prefix, signed = false).single().contains("holds no POM"))
    }

    @Test
    fun `the bundle holds the repository layout without Maven metadata`(@TempDir out: File) {
        stage()
        val entries = ZipFile(CentralBundle.zip(staging, out.resolve("bundle.zip"))).use { zip ->
            zip.entries().toList().map { it.name }
        }
        assertTrue("io/github/fiftieshousewife/java-app/0.0.3/java-app-0.0.3.jar.asc" in entries, entries.toString())
        assertTrue(entries.none { it.contains("maven-metadata") }, entries.toString())
    }
}
