package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArchTest {
    @Test
    fun `each CPU has the name every ecosystem gives it`() {
        val names = Arch.entries.map { listOf(it.id, it.rpm, it.container) }
        assertEquals(listOf(listOf("x64", "x86_64", "amd64"), listOf("aarch64", "aarch64", "arm64")), names)
    }
}
