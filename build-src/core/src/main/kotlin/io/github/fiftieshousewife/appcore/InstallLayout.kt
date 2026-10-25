package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * An install root on a server, which the launcher recognises (an app directory inside `releases/`
 * takes the directory above as `APP_ROOT`):
 *
 * ```
 * <root>/releases/<app>-<version>/   one unpacked archive per version
 * <root>/current -> releases/…       what runs
 * <root>/previous -> releases/…      what `appRollback` goes back to
 * <root>/conf/                       seeded from conf.defaults/ once per file, never overwritten
 * ```
 *
 * Links are symbolic links, switched with an atomic rename on Linux and macOS; on Windows they are
 * directory junctions, which need no administrator rights.
 */
class InstallLayout(val root: File, private val windows: Boolean = Target.host().isWindows) {
    val releases: File get() = root.resolve("releases")
    val conf: File get() = root.resolve("conf")
    private val currentLink: Path get() = root.toPath().resolve(CURRENT)
    private val previousLink: Path get() = root.toPath().resolve(PREVIOUS)

    /** The release `current` points at, if any. */
    val current: File? get() = target(currentLink)

    val previous: File? get() = target(previousLink)

    fun release(name: String, version: String): File = releases.resolve("$name-$version")

    /** Copies each file of [release]'s `conf.defaults/` into `conf/` unless it is already there. */
    fun seedConfig(release: File): List<String> {
        val defaults = release.resolve("conf.defaults").takeIf(File::isDirectory) ?: return emptyList()
        return defaults.walkTopDown().filter(File::isFile).mapNotNull { file ->
            val rel = file.relativeTo(defaults)
            conf.resolve(rel.path).takeUnless(File::exists)?.let { target ->
                file.copyTo(target)
                rel.invariantSeparatorsPath
            }
        }.toList()
    }

    /** Points `current` at [release], and `previous` at whatever `current` was. */
    fun switchTo(release: File) {
        val was = current
        if (was != null && was.canonicalFile != release.canonicalFile) link(previousLink, was)
        link(currentLink, release)
    }

    /** Swaps `current` and `previous`. */
    fun rollback() {
        val back = previous ?: throw GradleException("No previous release in $root to roll back to")
        val was = current ?: throw GradleException("Nothing is installed in $root")
        link(currentLink, back)
        link(previousLink, was)
    }

    /** Deletes all but the newest [keep] releases, never `current` or `previous`. */
    fun prune(keep: Int): List<String> {
        val inUse = setOfNotNull(current, previous).map(File::getCanonicalFile)
        val old = releases.listFiles(File::isDirectory).orEmpty()
            .filterNot { it.name.startsWith(".") }
            .sortedByDescending(File::lastModified)
            .drop(keep)
            .filterNot { it.canonicalFile in inUse }
        old.forEach(File::deleteRecursively)
        return old.map(File::getName)
    }

    private fun target(link: Path): File? = link.takeIf { Files.exists(it) }?.toRealPath()?.toFile()

    private fun link(link: Path, release: File) {
        // Both canonical: a root reached through a symlink (macOS /var) would otherwise give a broken link.
        val relative = root.canonicalFile.toPath().relativize(release.canonicalFile.toPath())
        if (windows) {
            Files.deleteIfExists(link)
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), release.absolutePath)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GradleException("Could not link $link to $release: $output")
            return
        }
        val fresh = link.resolveSibling("${link.fileName}.new")
        Files.deleteIfExists(fresh)
        Files.createSymbolicLink(fresh, relative)
        Files.move(fresh, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val CURRENT = "current"
        const val PREVIOUS = "previous"
    }
}
