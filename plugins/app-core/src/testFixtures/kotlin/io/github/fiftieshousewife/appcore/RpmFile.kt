package io.github.fiftieshousewife.appcore

import org.redline_rpm.ReadableChannelWrapper
import org.redline_rpm.Scanner
import org.redline_rpm.header.AbstractHeader
import org.redline_rpm.header.Header
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel

/** One file an RPM installs, as its header describes it. */
data class RpmEntry(val mode: Int, val flags: Int, val user: String, val group: String)

/** An RPM's header, read back with Redline so tests run on any machine, with or without `rpm`. */
class RpmFile(file: File) {
    private val header: Header =
        FileChannel.open(file.toPath()).use {
            Scanner(
                PrintStream(OutputStream.nullOutputStream(), false, Charsets.UTF_8),
            ).run(ReadableChannelWrapper(it)).header
        }

    val name: String get() = string(Header.HeaderTag.NAME)
    val version: String get() = string(Header.HeaderTag.VERSION)
    val release: String get() = string(Header.HeaderTag.RELEASE)
    val arch: String get() = string(Header.HeaderTag.ARCH)
    val preInstall: String get() = string(Header.HeaderTag.PREINSCRIPT)
    val requires: List<String> get() = strings(Header.HeaderTag.REQUIRENAME).toList()

    /** Every installed path, with its permissions, `%config` flags and owner. */
    val entries: Map<String, RpmEntry> by lazy {
        val dirs = strings(Header.HeaderTag.DIRNAMES)
        val indexes = values<IntArray>(Header.HeaderTag.DIRINDEXES)
        val modes = values<ShortArray>(Header.HeaderTag.FILEMODES)
        val flags = values<IntArray>(Header.HeaderTag.FILEFLAGS)
        val users = strings(Header.HeaderTag.FILEUSERNAME)
        val groups = strings(Header.HeaderTag.FILEGROUPNAME)
        strings(Header.HeaderTag.BASENAMES).withIndex().associate { (i, base) ->
            dirs[indexes[i]] + base to RpmEntry(modes[i].toInt() and PERMISSIONS, flags[i], users[i], groups[i])
        }
    }

    private fun string(tag: AbstractHeader.Tag) = strings(tag).single()

    private fun strings(tag: AbstractHeader.Tag) = values<Array<String>>(tag)

    private inline fun <reified T> values(tag: AbstractHeader.Tag): T =
        (header.getEntry(tag) ?: error("No ${tag.name} in the RPM header")).values as T

    private companion object {
        const val PERMISSIONS = 0b111_111_111
    }
}
