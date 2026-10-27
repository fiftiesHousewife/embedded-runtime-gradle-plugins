package io.github.fiftieshousewife.appcore

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.gradle.api.GradleException
import org.redline_rpm.ReadableChannelWrapper
import org.redline_rpm.header.Format
import org.redline_rpm.header.Header
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * The files inside an RPM, for a runtime packaged as one (a vendor's JDK RPM, or an organisation's own
 * repackaging). Redline reads the RPM headers; Commons Compress decompresses and reads the cpio
 * payload; this object only writes the entries out and finds the runtime's home among them.
 */
internal object RpmPayload {
    /** What RPM calls each payload compressor, in Commons Compress's names. */
    private val COMPRESSORS =
        mapOf(
            "gzip" to CompressorStreamFactory.GZIP,
            "xz" to CompressorStreamFactory.XZ,
            "lzma" to CompressorStreamFactory.LZMA,
            "bzip2" to CompressorStreamFactory.BZIP2,
            "zstd" to CompressorStreamFactory.ZSTANDARD,
        )
    private const val TYPE_MASK = 0xF000
    private const val PERMISSION_MASK = 0x1FF

    /**
     * Writes every file in [rpm] under [into], as installing it at [into] would, except that each
     * symbolic link becomes a copy of what it points to: links need privileges on Windows and would
     * make the unpacked runtime differ by OS. Returns the links left out because they point at
     * nothing in the package (a JDK's `cacerts` at the system's `/etc/pki/…`, say) or loop.
     */
    fun unpack(rpm: File, into: File): List<String> = FileChannel.open(rpm.toPath()).use { channel ->
        val header = Format().apply { read(ReadableChannelWrapper(channel)) }.header
        val compressor = (header.getEntry(Header.HeaderTag.PAYLOADCOMPRESSOR)?.values as? Array<*>)
            ?.firstOrNull()?.toString() ?: "gzip"
        decompressed(compressor, BufferedInputStream(Channels.newInputStream(channel))).use { payload ->
            CpioArchiveInputStream(payload).use { cpio -> write(cpio, into.toPath(), rpm.name) }
        }
    }

    /** [payload] decompressed as RPM's [compressor] says; `none` for an uncompressed payload. */
    fun decompressed(compressor: String, payload: InputStream): InputStream {
        if (compressor == "none") return payload
        val name = COMPRESSORS[compressor]
            ?: throw GradleException("RPM payload compressor '$compressor' is not supported; ${COMPRESSORS.keys} are")
        return CompressorStreamFactory().createCompressorInputStream(name, payload)
    }

    /**
     * The runtime's home among the unpacked files: the shallowest directory holding [marker] (`release`
     * for a JDK) beside a `bin` directory, wherever the package installs it.
     */
    fun home(unpacked: File, marker: String, rpmName: String): File =
        unpacked.walkTopDown().filter { it.isDirectory && it.resolve(marker).isFile && it.resolve("bin").isDirectory }
            .minByOrNull { it.toPath().nameCount }
            ?: throw GradleException("$rpmName holds no runtime: no directory has both $marker and bin/")

    private fun write(cpio: CpioArchiveInputStream, root: Path, rpmName: String): List<String> {
        // In a newc archive a hard-linked file's data comes with its last name; earlier names have none.
        val linkedNames = mutableMapOf<Long, MutableList<Path>>()
        val symlinks = mutableMapOf<Path, SymlinkCopies.Link>()
        generateSequence { cpio.nextEntry }.forEach { entry ->
            val path = safePath(root, entry.name, rpmName)
            Files.createDirectories(path.parent)
            when {
                entry.isDirectory -> Files.createDirectories(path)

                entry.isSymbolicLink -> String(cpio.readBytes(), Charsets.UTF_8).let { target ->
                    symlinks[path] = SymlinkCopies.Link(linkTarget(path, target, root), target)
                }

                entry.numberOfLinks > 1 && entry.size == 0L -> linkedNames.getOrPut(entry.inode) {
                    mutableListOf()
                }.add(path)

                else -> {
                    Files.copy(cpio, path, StandardCopyOption.REPLACE_EXISTING)
                    linkedNames.remove(entry.inode).orEmpty().forEach { Files.copy(path, it) }
                }
            }
            if (!entry.isSymbolicLink) permissions(path, entry)
        }
        return SymlinkCopies(root, symlinks).write()
    }

    /** Refuses names that would land outside [root]. */
    private fun safePath(root: Path, name: String, rpmName: String): Path {
        val path = root.resolve(name.removePrefix(".").trimStart('/')).normalize()
        if (!path.startsWith(root)) throw GradleException("$rpmName has an entry outside its root: $name")
        return path
    }

    /** Where a link points, in the unpacked tree: an absolute target is taken from the package's root. */
    private fun linkTarget(path: Path, target: String, root: Path): Path = if (target.startsWith(
            "/",
        )
    ) {
        root.resolve(target.trimStart('/')).normalize()
    } else {
        path.parent.resolve(target).normalize()
    }

    private fun permissions(path: Path, entry: CpioArchiveEntry) {
        val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")
        if ((entry.mode.toInt() and TYPE_MASK) == 0 || !posix) return
        val mode = entry.mode.toInt() and PERMISSION_MASK
        val byBit = PosixFilePermission.entries.reversed() // OTHERS_EXECUTE is bit 0, OWNER_READ bit 8
        val granted = byBit.filterIndexed { bit, _ -> ((mode shr bit) and 1) == 1 }.toSet()
        // Kept writable by its owner, so a later extraction can replace it.
        Files.setPosixFilePermissions(path, granted + PosixFilePermission.OWNER_WRITE)
    }
}

/**
 * Writes each link in [links] (where it sits, and what it points to) as a copy of its target, once
 * every file is in place: a link to a file becomes the file, a link to a directory the directory, a
 * link to a link whatever that one ends at. Links to directories are copied last, so the copies hold
 * the copies made for any links inside them.
 */
private class SymlinkCopies(private val root: Path, private val links: Map<Path, Link>) {
    /** Where a link points in the unpacked tree, and what the package wrote. */
    data class Link(val target: Path, val written: String)

    fun write(): List<String> {
        val skipped = mutableListOf<String>()
        val (toDirectories, toFiles) = links.keys.partition { finalTarget(it)?.let(Files::isDirectory) == true }
        (toFiles + toDirectories).forEach { link ->
            val target = finalTarget(link)
            when {
                target == null || !Files.exists(target) ->
                    skipped +=
                        "${root.relativize(link)} -> ${links.getValue(link).written}"

                Files.isDirectory(target) -> copyTree(target, link)

                else -> Files.copy(
                    target,
                    link,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
        }
        return skipped
    }

    /** What [link] ends at after any chain of links, or null outside the package or in a loop. */
    private fun finalTarget(link: Path): Path? {
        val hops = generateSequence(links.getValue(link).target) { links[it]?.target }.take(MAX_HOPS + 1).toList()
        return hops.last().takeIf { hops.size <= MAX_HOPS && hops.all { it.startsWith(root) } }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val destination = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(
                        source,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                }
            }
        }
    }

    private companion object {
        /** As many links in a chain as Linux follows before giving up (ELOOP). */
        const val MAX_HOPS = 40
    }
}
