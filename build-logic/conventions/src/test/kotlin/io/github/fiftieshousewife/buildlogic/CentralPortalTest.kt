package io.github.fiftieshousewife.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64

class CentralPortalTest {
    private val portal = CentralPortal("token-user", "token-secret")

    @Test
    fun `the upload names the deployment after the project and publishes it automatically`() {
        val request = portal.uploadRequest(byteArrayOf(1, 2, 3), "embedded-runtime-gradle-plugins-0.0.3", automatic = true)
        assertEquals(
            "https://central.sonatype.com/api/v1/publisher/upload?name=embedded-runtime-gradle-plugins-0.0.3&publishingType=AUTOMATIC",
            request.uri().toString(),
        )
    }

    @Test
    fun `the upload authenticates with the user token and sends the bundle as a multipart file`() {
        val request = portal.uploadRequest(byteArrayOf(1, 2, 3), "x", automatic = false)
        val token = Base64.getDecoder().decode(request.headers().firstValue("Authorization").get().removePrefix("Bearer "))
        assertEquals("token-user:token-secret", String(token))
        assertEquals(
            "multipart/form-data; boundary=${CentralPortal.BOUNDARY}",
            request.headers().firstValue("Content-Type").get(),
        )
        assertEquals("USER_MANAGED", request.uri().query.substringAfter("publishingType="))
    }
}
