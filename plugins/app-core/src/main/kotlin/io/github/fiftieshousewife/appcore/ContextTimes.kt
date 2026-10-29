package io.github.fiftieshousewife.appcore

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.Instant

/**
 * `COPY` records each file's time in the image layer, so a context written by each build gives a
 * different runtime layer every time, which a registry then stores and every node pulls again.
 */
internal object ContextTimes {
    /** The archives' own entry time. */
    val FIXED: FileTime = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"))

    /** Links keep their own time rather than their target's: `COPY` copies the link. */
    fun fix(root: Path) {
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                Files.getFileAttributeView(path, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .setTimes(FIXED, FIXED, null)
            }
        }
    }
}
