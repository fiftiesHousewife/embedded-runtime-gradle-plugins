package io.github.fiftieshousewife.buildlogic

import groovy.json.JsonSlurper
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The Central Portal's Publisher API: upload a bundle as one named deployment, then follow it
 * until Central has validated it. https://central.sonatype.org/publish/publish-portal-api/
 */
class CentralPortal(
    private val username: String,
    private val password: String,
    private val baseUrl: String = "https://central.sonatype.com",
) {
    private val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .proxy(ProxySelector.getDefault())
            .authenticator(proxyLogin())
            .build()

    /** The upload request: named [deploymentName], published as soon as it validates when [automatic]. */
    fun uploadRequest(bundle: ByteArray, deploymentName: String, automatic: Boolean): HttpRequest {
        val type = if (automatic) "AUTOMATIC" else "USER_MANAGED"
        val name = URLEncoder.encode(deploymentName, Charsets.UTF_8)
        val body = multipart(bundle)
        return HttpRequest.newBuilder(URI.create("$baseUrl/api/v1/publisher/upload?name=$name&publishingType=$type"))
            .timeout(Duration.ofMinutes(UPLOAD_MINUTES))
            .header("Authorization", authorization())
            .header("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
    }

    /** Uploads [bundle] and returns the deployment's id. */
    fun upload(bundle: ByteArray, deploymentName: String, automatic: Boolean): String {
        val response = client.send(uploadRequest(bundle, deploymentName, automatic), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in SUCCESS) { "Upload refused: HTTP ${response.statusCode()} ${response.body()}" }
        return response.body().trim()
    }

    /** The deployment's state (`PENDING`, `VALIDATING`, `VALIDATED`, `PUBLISHING`, `PUBLISHED`, `FAILED`) and errors. */
    @Suppress("UNCHECKED_CAST")
    fun status(deploymentId: String): Pair<String, String> {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1/publisher/status?id=$deploymentId"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Authorization", authorization())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in SUCCESS) { "Status refused: HTTP ${response.statusCode()} ${response.body()}" }
        val json = JsonSlurper().parseText(response.body()) as Map<String, Any?>
        return json["deploymentState"].toString() to (json["errors"]?.toString() ?: "")
    }

    fun authorization(): String =
        "Bearer " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    private fun multipart(bundle: ByteArray): ByteArray {
        val head = "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"bundle.zip\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        return head.toByteArray() + bundle + "\r\n--$BOUNDARY--\r\n".toByteArray()
    }

    /** Answers a proxy's challenge with the standard JVM proxy properties, as Gradle's own downloads do. */
    private fun proxyLogin() = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != RequestorType.PROXY) return null
            val user = System.getProperty("https.proxyUser") ?: System.getProperty("http.proxyUser") ?: return null
            val secret = System.getProperty("https.proxyPassword") ?: System.getProperty("http.proxyPassword").orEmpty()
            return PasswordAuthentication(user, secret.toCharArray())
        }
    }

    companion object {
        const val BOUNDARY = "embedded-runtime-bundle"
        private const val TIMEOUT_SECONDS = 60L
        private const val UPLOAD_MINUTES = 10L
        private val SUCCESS = 200..299
    }
}
