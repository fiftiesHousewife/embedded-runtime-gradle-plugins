package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProxySettingsTest {
    @Test
    fun `no proxy properties means no proxy, which is normal inside a corporate network`() {
        val settings = ProxySettings.fromSystem { null }
        assertEquals(emptyList<String>(), settings.npmrcLines())
        assertNull(ProxySettings.proxyLogin { null })
    }

    @Test
    fun `the JVM proxy properties become npm's proxy settings`() {
        val props =
            mapOf(
                "https.proxyHost" to "proxy.example.com",
                "https.proxyPort" to "3128",
                "https.proxyUser" to "build",
                "https.proxyPassword" to "secret",
                "http.nonProxyHosts" to "localhost|*.example.com",
            )
        assertEquals(
            listOf("https-proxy=http://build:secret@proxy.example.com:3128", "noproxy=localhost,.example.com"),
            ProxySettings.fromSystem(props::get).npmrcLines(),
        )
    }
}
