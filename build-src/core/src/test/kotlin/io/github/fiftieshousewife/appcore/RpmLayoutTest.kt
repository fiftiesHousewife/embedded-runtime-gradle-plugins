package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RpmLayoutTest {
    private fun layout(version: String = "1.4.2", target: Target = Target.LINUX_X64, user: String = "orders") =
        RpmLayout("orders", version, "1", target, user, "orders-svc", "Orders service")

    @Test
    fun `names the file the way dnf and rpm expect, with RPM architecture names`() {
        assertAll(
            { assertEquals("orders-1.4.2-1.x86_64.rpm", layout().fileName) },
            { assertEquals("orders-1.4.2-1.aarch64.rpm", layout(target = Target.LINUX_AARCH64).fileName) },
        )
    }

    @Test
    fun `turns a Maven qualifier into an RPM pre-release that sorts before the release`() {
        assertEquals("1.4.2~SNAPSHOT", layout("1.4.2-SNAPSHOT").rpmVersion)
    }

    @Test
    fun `refuses targets and names RPM cannot express`() {
        assertAll(
            { assertThrows<GradleException> { layout(target = Target.WINDOWS_X64) } },
            { assertThrows<GradleException> { layout(user = "Orders Service") } },
            { assertThrows<GradleException> { layout("1.4.2 beta").rpmVersion } },
        )
    }

    @Test
    fun `the unit runs the launcher as the service account with the system read-only`() {
        val unit = layout().render(RpmLayout.template("app.service"))
        assertAll(
            { assertTrue(unit.contains("User=orders\n"), unit) },
            { assertTrue(unit.contains("ExecStart=/opt/orders/bin/orders-svc run\n"), unit) },
            { assertTrue(unit.contains("Environment=APP_CONF_DIR=/etc/orders\n"), unit) },
            { assertTrue(unit.contains("ProtectSystem=strict\n"), unit) },
            { assertTrue(unit.contains("ReadWritePaths=/var/lib/orders /var/log/orders\n"), unit) },
            { assertFalse(unit.contains("@"), unit) },
        )
    }

    @Test
    fun `the scriptlets create a login-less account and restart only on upgrade`() {
        val pre = layout().render(RpmLayout.template("pre.sh"))
        val postun = layout().render(RpmLayout.template("postun.sh"))
        assertAll(
            { assertTrue(pre.contains("useradd -r -g orders -d /var/lib/orders -s /sbin/nologin"), pre) },
            { assertTrue(postun.contains("""if [ "$1" -ge 1 ]; then systemctl try-restart orders.service"""), postun) },
        )
    }
}
