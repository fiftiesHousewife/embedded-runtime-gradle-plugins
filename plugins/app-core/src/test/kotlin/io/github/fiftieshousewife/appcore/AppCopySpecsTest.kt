package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppCopySpecsTest {
    @Test
    fun `launchers, runtime binaries and the JDK's process helpers are executable`() {
        assertAll(
            { assertTrue(AppCopySpecs.isExecutable("bin/orders")) },
            { assertTrue(AppCopySpecs.isExecutable("runtime/bin/java")) },
            { assertTrue(AppCopySpecs.isExecutable("runtime/lib/jspawnhelper")) },
        )
    }

    @Test
    fun `libraries, configuration and other runtime files are not`() {
        assertAll(
            { assertFalse(AppCopySpecs.isExecutable("lib/orders.jar")) },
            { assertFalse(AppCopySpecs.isExecutable("conf.defaults/app.properties")) },
            { assertFalse(AppCopySpecs.isExecutable("runtime/lib/modules")) },
        )
    }
}
