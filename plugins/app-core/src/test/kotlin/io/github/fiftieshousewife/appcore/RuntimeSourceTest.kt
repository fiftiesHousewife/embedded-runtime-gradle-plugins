package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.io.File
import java.net.URI

class RuntimeSourceTest {
    @Test
    fun `each form of source is told apart`() {
        val expected =
            mapOf(
                null to RuntimeSource.Default,
                " " to RuntimeSource.Default,
                "https://mirror.example.com/adoptium" to RuntimeSource.Mirror("https://mirror.example.com/adoptium"),
                "file:///srv/mirror" to RuntimeSource.Mirror("file:///srv/mirror"),
                "/srv/jdks" to RuntimeSource.Directory(URI.create(File("/srv/jdks").toURI().toString() + "/")),
                "jdks-local" to RuntimeSource.Repository("jdks-local"),
                "yum:rhel/9/" to RuntimeSource.Yum("rhel/9"),
                "yum:https://repo.example.com/yum" to RuntimeSource.Yum("https://repo.example.com/yum"),
            )
        assertAll(
            expected.map { (value, source) ->
                Executable { assertEquals(source, RuntimeSource.parse("javaApp.jdk.source", value), value) }
            },
        )
    }

    @Test
    fun `a YUM repository is a URL or a name beside the binary repository`() {
        assertAll(
            { assertEquals(true, RuntimeSource.Yum("file:///srv/yum").isUrl) },
            { assertEquals(false, RuntimeSource.Yum("rhel/9").isUrl) },
        )
    }

    @Test
    fun `anything else with a colon in it is refused`() {
        val failure = assertThrows(GradleException::class.java) {
            RuntimeSource.parse("javaApp.jdk.source", "ftp://jdks.example.com")
        }
        assertEquals(
            "javaApp.jdk.source=ftp://jdks.example.com is not a URL, an absolute path, yum:… or a repository name",
            failure.message,
        )
    }
}
