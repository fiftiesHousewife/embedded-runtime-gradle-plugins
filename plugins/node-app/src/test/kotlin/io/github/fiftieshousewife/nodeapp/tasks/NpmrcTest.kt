package io.github.fiftieshousewife.nodeapp.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NpmrcTest {
    @Test
    fun `points npm at the registry and replaces the host recorded in lockfiles`() {
        assertEquals(
            listOf("registry=https://registry.npmjs.org/", "replace-registry-host=always"),
            Npmrc.lines("https://registry.npmjs.org", null, null),
        )
    }

    @Test
    fun `adds a corporate CA and credentials scoped to the registry's own path`() {
        assertEquals(
            listOf(
                "registry=https://art.example.com/api/npm/npm-virtual/",
                "replace-registry-host=always",
                "cafile=/etc/ca.pem",
                "//art.example.com/api/npm/npm-virtual/:_auth=dXNlcjpzZWNyZXQ=",
            ),
            Npmrc.lines("https://art.example.com/api/npm/npm-virtual/", "/etc/ca.pem", NpmUser("user", "secret")),
        )
    }
}
