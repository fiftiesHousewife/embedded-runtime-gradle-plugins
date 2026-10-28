package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class RuntimePolicySourceTest {
    @Test
    fun `a policy is coordinates only when it is three parts with no path in them`() {
        val expected =
            mapOf(
                "com.example.security:runtime-policy:2026.10" to true,
                "policy" to false,
                "../shared/runtime-policy.zip" to false,
                "C:\\policies\\runtime" to false,
                "C:/policies/runtime.zip" to false,
                "/srv/policy:v2" to false,
            )
        assertAll(
            expected.map { (policy, coordinates) ->
                Executable { assertEquals(coordinates, RuntimePolicySource.isCoordinates(policy), policy) }
            },
        )
    }
}
