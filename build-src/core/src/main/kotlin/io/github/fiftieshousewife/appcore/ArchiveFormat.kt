package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileTree
import java.io.File

/** Every format records Unix permissions, so `bin/` stays executable however the archive is unpacked. */
enum class ArchiveFormat(val id: String) {
    TAR_GZ("tar.gz"),

    /** Uncompressed, for a pipeline that compresses (or signs, or layers) the archive itself. */
    TAR("tar"),

    ZIP("zip"),
    ;

    val extension: String get() = id

    override fun toString(): String = id

    companion object {
        fun nativeTo(target: Target): ArchiveFormat = if (target.isWindows) ZIP else TAR_GZ

        fun parse(id: String): ArchiveFormat = entries.firstOrNull { it.id == id.trim().lowercase() }
            ?: throw GradleException("Unsupported archive format '$id'. Supported: ${entries.joinToString()}")

        fun of(file: File): ArchiveFormat = entries.firstOrNull { file.name.endsWith(".${it.extension}") }
            ?: throw GradleException("${file.name} is not an archive of a supported format: ${entries.joinToString()}")
    }
}

/** The contents of [file], whichever [ArchiveFormat] (or `.tgz`/`.tar.xz` runtime download) it is. */
fun ArchiveOperations.archiveTree(file: File): FileTree =
    if (file.name.endsWith(".zip")) zipTree(file) else tarTree(file)
