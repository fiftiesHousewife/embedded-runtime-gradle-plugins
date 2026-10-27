package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileTree
import java.io.File

/**
 * The archive every target's app is packed into. Whatever the format, an archive holds one
 * top-level directory `<app>-<version>/` with Unix permissions recorded, so `bin/` stays
 * executable however it is unpacked.
 */
enum class ArchiveFormat(val id: String) {
    /** Linux's and macOS's: smallest for a runtime, opened by `tar` and the Finder. */
    TAR_GZ("tar.gz"),

    /** Uncompressed, for a pipeline that compresses (or signs, or layers) the archive itself. */
    TAR("tar"),

    /** Windows's: what Explorer opens without a separate tool. */
    ZIP("zip"),
    ;

    /** The file extension, which is also the Maven artifact extension. */
    val extension: String get() = id

    override fun toString(): String = id

    companion object {
        /** The format [target]'s own OS opens without a separate tool: `zip` on Windows, else `tar.gz`. */
        fun nativeTo(target: Target): ArchiveFormat = if (target.isWindows) ZIP else TAR_GZ

        fun parse(id: String): ArchiveFormat = entries.firstOrNull { it.id == id.trim().lowercase() }
            ?: throw GradleException("Unsupported archive format '$id'. Supported: ${entries.joinToString()}")

        /** The format of an existing archive, from its name. */
        fun of(file: File): ArchiveFormat = entries.firstOrNull { file.name.endsWith(".${it.extension}") }
            ?: throw GradleException("${file.name} is not an archive of a supported format: ${entries.joinToString()}")
    }
}

/** The contents of [file], whichever [ArchiveFormat] (or `.tgz`/`.tar.xz` runtime download) it is. */
fun ArchiveOperations.archiveTree(file: File): FileTree =
    if (file.name.endsWith(".zip")) zipTree(file) else tarTree(file)
