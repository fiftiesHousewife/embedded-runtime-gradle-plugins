package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NodeSourcesTest {
    private val public = NodeSources(binaryRepo = null)

    @Test
    fun `every supported target has a nodejs-org classifier`() {
        assertEquals(
            listOf("linux-x64", "linux-arm64", "win-x64", "darwin-x64", "darwin-arm64"),
            Target.SUPPORTED.map(NodeSources::distClassifier),
        )
    }

    @Test
    fun `node urls match nodejs-org dist`() {
        assertEquals(
            "https://nodejs.org/dist/v24.18.1/node-v24.18.1-darwin-arm64.tar.gz",
            public.repository.downloadUri(public.artifact("24.18.1", Target.MAC_AARCH64)).toString(),
        )
        assertEquals(
            "https://nodejs.org/dist/v24.18.1/node-v24.18.1-win-x64.zip",
            public.repository.downloadUri(public.artifact("24.18.1", Target.WINDOWS_X64)).toString(),
        )
    }
}
