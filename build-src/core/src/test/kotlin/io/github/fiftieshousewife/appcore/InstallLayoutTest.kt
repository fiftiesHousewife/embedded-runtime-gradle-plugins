package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InstallLayoutTest {
    @TempDir
    lateinit var root: File

    private val layout by lazy { InstallLayout(root) }

    private fun release(version: String, modified: Long = 0) = layout.release("svc", version).apply {
        resolve("conf.defaults").mkdirs()
        resolve("conf.defaults/app.properties").writeText("key=$version\n")
        if (modified > 0) setLastModified(modified)
    }

    @Test
    fun `configuration is seeded once per file and never overwritten`() {
        val first = layout.seedConfig(release("1.0.0"))
        layout.conf.resolve("app.properties").writeText("key=edited\n")
        val second = layout.seedConfig(release("1.1.0"))
        assertAll(
            { assertEquals(listOf("app.properties"), first) },
            { assertEquals(emptyList<String>(), second) },
            { assertEquals("key=edited\n", layout.conf.resolve("app.properties").readText()) },
        )
    }

    @Test
    fun `switching keeps the old release as previous, and rollback swaps them back`() {
        val old = release("1.0.0")
        val new = release("1.1.0")
        val empty = layout.current
        layout.switchTo(old)
        layout.switchTo(new)
        val afterSwitch = layout.current to layout.previous
        layout.rollback()
        assertAll(
            { assertNull(empty) },
            { assertEquals(new.canonicalFile to old.canonicalFile, afterSwitch) },
            { assertEquals(old.canonicalFile, layout.current) },
            { assertEquals(new.canonicalFile, layout.previous) },
        )
    }

    @Test
    fun `the links are relative, so the whole root can be moved`() {
        layout.switchTo(release("1.0.0"))
        val moved = root.parentFile.resolve("${root.name}-moved")
        check(root.renameTo(moved))
        try {
            assertEquals(moved.resolve("releases/svc-1.0.0").canonicalFile, InstallLayout(moved).current)
        } finally {
            moved.renameTo(root)
        }
    }

    @Test
    fun `pruning keeps the newest releases and never current or previous`() {
        val oldest = release("1.0.0", modified = 1_000_000)
        release("1.1.0", modified = 2_000_000)
        release("1.2.0", modified = 3_000_000)
        val newest = release("1.3.0", modified = 4_000_000)
        layout.switchTo(oldest)
        layout.switchTo(newest)
        val pruned = layout.prune(keep = 2)
        assertAll(
            { assertEquals(listOf("svc-1.1.0"), pruned) },
            { assertTrue(oldest.isDirectory, "previous must survive") },
            { assertTrue(layout.release("svc", "1.2.0").isDirectory) },
        )
    }
}
