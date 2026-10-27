package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class SourceUrlTest {
    @Test
    fun `turns a git remote into an https URL with no credentials`() {
        val expected =
            mapOf(
                "https://github.com/example/orders.git" to "https://github.com/example/orders.git",
                "https://build-bot:ghp_secret@github.com/example/orders" to "https://github.com/example/orders",
                "https://token@git.example.com:8443/team/orders" to "https://git.example.com:8443/team/orders",
                "git@github.com:example/orders.git" to "https://github.com/example/orders.git",
                "ssh://git@git.example.com:7999/team/orders.git" to "https://git.example.com/team/orders.git",
            )
        assertAll(
            expected.map { (remote, url) -> Executable { assertEquals(url, SourceUrl.fromRemote(remote), remote) } },
        )
    }

    @Test
    fun `gives nothing for a remote that is not on a server`() {
        val local = listOf("/srv/git/orders.git", "file:///srv/git/orders.git", "../orders", "C:/git/orders")
        assertAll(local.map { remote -> Executable { assertNull(SourceUrl.fromRemote(remote), remote) } })
    }
}
