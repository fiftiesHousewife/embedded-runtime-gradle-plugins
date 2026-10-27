package io.github.fiftieshousewife.javaapp.tasks

import io.github.fiftieshousewife.appcore.ProxySettings
import org.gradle.api.GradleException
import java.io.File
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * Fetches a file by URI and refuses it unless its SHA-256 is the one pinned: for content the
 * runtime will trust, such as CA certificates. `file:` URIs are read directly; `https:` (and
 * `http:`) through the JVM's proxy settings, with [login] answering the server's challenge.
 */
internal class PinnedDownload(private val login: (URI) -> PasswordAuthentication?) {
    fun fetch(uri: URI, sha256: String, into: File): File {
        val name = uri.path.substringAfterLast('/').ifEmpty { "certificate.pem" }
        val bytes = if (uri.scheme == "file") Files.readAllBytes(File(uri).toPath()) else download(uri)
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        if (actual != sha256) {
            throw GradleException("$uri has SHA-256 $actual, not the pinned $sha256: refusing to trust it")
        }
        return into.resolve(name).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun download(uri: URI): ByteArray {
        val client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .authenticator(ProxySettings.authenticator(login(uri)))
            .build()
        val response = client.send(
            HttpRequest.newBuilder(uri).timeout(TIMEOUT).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() !in
            SUCCESS
        ) {
            throw GradleException("Fetching $uri failed: HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(60)
        val SUCCESS = 200..299
    }
}
