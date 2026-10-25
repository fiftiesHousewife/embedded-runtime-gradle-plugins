package io.github.fiftieshousewife.nodeapp

import org.junit.jupiter.api.Assertions.assertEquals
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
}
