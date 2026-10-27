package io.github.fiftieshousewife.appcore

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Writes a YUM repository the way `createrepo` and Artifactory lay one out: the packages under
 * `Packages/`, a gzipped primary index listing them, and `repodata/repomd.xml` naming that index.
 */
object YumRepositoryFixture {
    /** One package to list: the RPM file, and what the metadata says about it. */
    data class Entry(val rpm: File, val name: String, val version: String, val release: String, val arch: String)

    private const val PRIMARY = "repodata/abc123-primary.xml.gz"

    fun write(root: File, entries: List<Entry>, checksumOf: (Entry) -> String = { sha256(it.rpm) }): File {
        val packages = entries.joinToString("\n") { entry -> packageXml(root, entry, checksumOf(entry)) }
        val primary = root.resolve(PRIMARY).apply { parentFile.mkdirs() }
        GZIPOutputStream(Files.newOutputStream(primary.toPath())).bufferedWriter().use {
            it.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <metadata xmlns="http://linux.duke.edu/metadata/common" xmlns:rpm="http://linux.duke.edu/metadata/rpm"
                  packages="${entries.size}">
                $packages
                </metadata>
                """.trimIndent(),
            )
        }
        root.resolve("repodata/repomd.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <repomd xmlns="http://linux.duke.edu/metadata/repo" xmlns:rpm="http://linux.duke.edu/metadata/rpm">
              <data type="filelists"><location href="repodata/def456-filelists.xml.gz"/></data>
              <data type="primary"><location href="$PRIMARY"/></data>
            </repomd>
            """.trimIndent(),
        )
        return root
    }

    /** One `<package>`, its file copied to where its location says; the `rpm:entry` is a decoy version. */
    private fun packageXml(root: File, entry: Entry, checksum: String): String {
        val location = "Packages/${entry.name.first()}/${entry.rpm.name}"
        root.resolve(location).apply { parentFile.mkdirs() }.also { entry.rpm.copyTo(it, overwrite = true) }
        return """
            <package type="rpm">
              <name>${entry.name}</name>
              <arch>${entry.arch}</arch>
              <version epoch="0" ver="${entry.version}" rel="${entry.release}"/>
              <checksum type="sha256" pkgid="YES">$checksum</checksum>
              <location href="$location"/>
              <format>
                <rpm:provides><rpm:entry name="decoy" ver="9.9.9"/></rpm:provides>
              </format>
            </package>
        """.trimIndent()
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
        "%02x".format(Locale.ROOT, it)
    }
}
