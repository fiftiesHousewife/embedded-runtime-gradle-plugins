package io.github.fiftieshousewife.nodeapp

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeLaunchCommandTest {
    private val launch = NodeLaunch("server/main.mjs", listOf("--enable-source-maps"))

    @Test
    fun `the POSIX launcher runs the entry point under app with the fixed options first`() {
        assertEquals(
            "exec \"\$APP_HOME/runtime/bin/node\" '--enable-source-maps' \${APP_NODE_OPTS:-} " +
                "\"\$APP_HOME/app/server/main.mjs\" \"\$@\"",
            NodeLaunchCommand.posix(launch),
        )
    }

    @Test
    fun `the Windows launcher writes the entry point with backslashes`() {
        assertEquals(
            "\"%APP_HOME%\\runtime\\node.exe\" \"--enable-source-maps\" %APP_NODE_OPTS% " +
                "\"%APP_HOME%\\app\\server\\main.mjs\"%ARGS%",
            NodeLaunchCommand.windows(launch),
        )
    }

    @Test
    fun `a CA bundle is exported before the POSIX launcher starts node`() {
        assertTrue(
            NodeLaunchCommand.posix(launch.copy(extraCaCerts = true)).startsWith(
                "NODE_EXTRA_CA_CERTS=\"\$APP_HOME/security/trusted-cas.pem\"; export NODE_EXTRA_CA_CERTS\n" +
                    "exec ",
            ),
        )
    }

    @Test
    fun `a CA bundle is set before the Windows launcher starts node`() {
        assertTrue(
            NodeLaunchCommand.windows(launch.copy(extraCaCerts = true)).startsWith(
                "set \"NODE_EXTRA_CA_CERTS=%APP_HOME%\\security\\trusted-cas.pem\"\n\"%APP_HOME%",
            ),
        )
    }

    @Test
    fun `TLS settings become Node options`() {
        assertEquals(
            listOf("--tls-min-v1.3", "--tls-cipher-list=TLS_AES_256_GCM_SHA384"),
            NodeRuntimeSecurity.options("1.3", "TLS_AES_256_GCM_SHA384"),
        )
    }

    @Test
    fun `no TLS settings add no options`() {
        assertEquals(emptyList<String>(), NodeRuntimeSecurity.options(null, null))
    }

    @Test
    fun `an unknown TLS version is refused`() {
        assertThrows(GradleException::class.java) { NodeRuntimeSecurity.options("1.1", null) }
    }
}
