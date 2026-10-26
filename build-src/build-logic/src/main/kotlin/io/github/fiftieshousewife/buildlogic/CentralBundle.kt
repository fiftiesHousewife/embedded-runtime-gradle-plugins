package io.github.fiftieshousewife.buildlogic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A release staged for Maven Central: a Maven repository layout in a directory, which is checked,
 * zipped and uploaded as one deployment. Checked here rather than by Central, so a release that
 * would show up badly named, or be rejected, fails before anything leaves the machine.
 */
object CentralBundle {
    /** What Central computes itself and rejects in a bundle; the Portal rebuilds the metadata. */
    private val notUploaded = Regex("""maven-metadata\.xml.*""")
    private val checksums = listOf("md5", "sha1")
    private val derived = Regex(""".*\.(asc|md5|sha1|sha256|sha512)$""")
    private val requiredElements = listOf("description", "url", "licenses", "developers", "scm")

    /**
     * Every reason [staging] is not ready to publish: a POM whose name doesn't start with
     * [namePrefix] or that lacks what Central requires, an artifact without its checksums, or,
     * when [signed], without its signature. Empty when it is ready.
     */
    fun problems(staging: File, namePrefix: String, signed: Boolean): List<String> {
        val files = staging.walkTopDown().filter(File::isFile).filterNot { notUploaded.matches(it.name) }.toList()
        val poms = files.filter { it.name.endsWith(".pom") }
        if (poms.isEmpty()) return listOf("$staging holds no POM: nothing was staged")
        val names = files.map { it.relativeTo(staging).invariantSeparatorsPath }.toSet()
        val artifacts = files.filterNot { derived.matches(it.name) }
        return poms.sortedBy { it.path }.flatMap { pomProblems(it, staging, namePrefix) } +
            artifacts.sortedBy { it.path }.flatMap { artifact ->
                val path = artifact.relativeTo(staging).invariantSeparatorsPath
                (checksums + listOfNotNull("asc".takeIf { signed }))
                    .filterNot { "$path.$it" in names }
                    .map { "$path has no .$it" }
            }
    }

    private fun pomProblems(pom: File, staging: File, namePrefix: String): List<String> {
        val path = pom.relativeTo(staging).invariantSeparatorsPath
        val project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
        fun child(tag: String) = (0 until project.childNodes.length).map(project.childNodes::item).firstOrNull { it.nodeName == tag }
        val name = child("name")?.textContent?.trim().orEmpty()
        return listOfNotNull(
            "$path is named '$name', not '$namePrefix…'".takeUnless { name.startsWith(namePrefix) },
        ) + requiredElements.filter { child(it)?.textContent.isNullOrBlank() }.map { "$path has no <$it>" }
    }

    /** [staging] as the zip the Publisher API takes: the repository layout, without Maven metadata. */
    fun zip(staging: File, into: File): File {
        ZipOutputStream(into.outputStream().buffered()).use { zip ->
            staging.walkTopDown().filter(File::isFile).filterNot { notUploaded.matches(it.name) }
                .sortedBy { it.relativeTo(staging).invariantSeparatorsPath }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(staging).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return into
    }
}
