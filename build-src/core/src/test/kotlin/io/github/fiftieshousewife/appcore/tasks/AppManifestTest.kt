package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AppManifestTest {
    @TempDir
    lateinit var home: Path

    private fun manifest(json: String) {
        Files.createDirectories(home.resolve("META-INF"))
        Files.writeString(home.resolve("META-INF/app.json"), json)
    }

    @Test
    fun `reads the name, version and target an archive describes itself with`() {
        manifest("""{"name": "svc", "version": "2.0.0", "target": "linux-x64", "ecosystem": "java"}""")
        assertEquals(AppManifest("svc", "2.0.0", "linux-x64"), AppManifest.read(home.toFile()))
    }

    @Test
    fun `refuses a directory that is not an app archive`() {
        assertThrows(GradleException::class.java) { AppManifest.read(home.toFile()) }
    }

    @Test
    fun `refuses a manifest missing a field it needs`() {
        manifest("""{"name": "svc", "version": "2.0.0"}""")
        assertThrows(GradleException::class.java) { AppManifest.read(home.toFile()) }
    }
}
