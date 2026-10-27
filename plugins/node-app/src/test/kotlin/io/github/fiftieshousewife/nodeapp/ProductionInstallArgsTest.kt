package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.Target
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductionInstallArgsTest {
    @Test
    fun `a build for this machine only skips install scripts`() {
        assertAll(
            {
                assertEquals(
                    listOf("--ignore-scripts"),
                    ProductionInstallArgs.of(true, Target.MAC_AARCH64, Target.MAC_AARCH64),
                )
            },
            {
                assertEquals(
                    emptyList<String>(),
                    ProductionInstallArgs.of(false, Target.MAC_AARCH64, Target.MAC_AARCH64),
                )
            },
        )
    }

    @Test
    fun `a build for another target asks npm for that target's optional dependencies`() {
        assertAll(
            {
                assertEquals(
                    listOf("--ignore-scripts", "--os=linux", "--cpu=arm64", "--libc=glibc"),
                    ProductionInstallArgs.of(true, Target.LINUX_AARCH64, Target.MAC_AARCH64),
                )
            },
            {
                assertEquals(
                    listOf("--os=win32", "--cpu=x64"),
                    ProductionInstallArgs.of(false, Target.WINDOWS_X64, Target.MAC_AARCH64),
                )
            },
        )
    }
}
