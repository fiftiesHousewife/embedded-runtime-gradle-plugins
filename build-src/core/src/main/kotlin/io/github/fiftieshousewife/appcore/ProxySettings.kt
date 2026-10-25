package io.github.fiftieshousewife.appcore

import java.net.Authenticator
import java.net.PasswordAuthentication

/**
 * The HTTP proxy, from the standard JVM properties Gradle already uses for every download it makes:
 * `systemProp.https.proxyHost`, `…proxyPort`, `…proxyUser`, `…proxyPassword` and
 * `systemProp.http.nonProxyHosts` in `gradle.properties`. Inside a corporate network the binary
 * repository is usually reached directly and none of this is set.
 *
 * For what Gradle doesn't download itself: npm (as `.npmrc` settings) and uploads made with the
 * JDK's `HttpClient`.
 */
data class ProxySettings(val http: String?, val https: String?, val noProxy: List<String>) {
    /** Lines for an `.npmrc`; npm writes `*.example.com` as `.example.com`. */
    fun npmrcLines(): List<String> = listOfNotNull(
        http?.let { "proxy=$it" },
        https?.let { "https-proxy=$it" },
        noProxy.takeIf { it.isNotEmpty() }?.joinToString(",", "noproxy=") { it.removePrefix("*") },
    )

    companion object {
        fun fromSystem(property: (String) -> String? = System::getProperty) = ProxySettings(
            http = url(property, "http", DEFAULT_HTTP_PORT),
            https = url(property, "https", DEFAULT_HTTPS_PORT),
            noProxy = property("http.nonProxyHosts").orEmpty().split('|').map(String::trim).filter(String::isNotEmpty),
        )

        /** The proxy's login, from `https.proxyUser`/`proxyPassword` (else the `http.` ones), if set. */
        fun proxyLogin(property: (String) -> String? = System::getProperty): PasswordAuthentication? {
            val user = property("https.proxyUser") ?: property("http.proxyUser") ?: return null
            val password = (property("https.proxyPassword") ?: property("http.proxyPassword")).orEmpty()
            return PasswordAuthentication(user, password.toCharArray())
        }

        /**
         * Answers the proxy's challenges with [proxyLogin] and the server's with [server]. One
         * authenticator for both: `HttpClient` drops a hand-set `Authorization` header once it has one.
         */
        fun authenticator(server: PasswordAuthentication?, property: (String) -> String? = System::getProperty) =
            object : Authenticator() {
                private val proxy = proxyLogin(property)

                override fun getPasswordAuthentication() = if (requestorType == RequestorType.PROXY) proxy else server
            }

        private fun url(property: (String) -> String?, scheme: String, defaultPort: Int): String? {
            val host = property("$scheme.proxyHost")?.takeIf(String::isNotBlank) ?: return null
            val port = property("$scheme.proxyPort") ?: defaultPort.toString()
            val user = property("$scheme.proxyUser")
            val credentials = user?.let { "$it:${property("$scheme.proxyPassword").orEmpty()}@" }.orEmpty()
            return "http://$credentials$host:$port"
        }

        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443
    }
}
