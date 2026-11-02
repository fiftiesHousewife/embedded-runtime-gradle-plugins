package io.github.fiftieshousewife.appcore

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RpmVersionsTest {
    private fun newer(a: String, b: String): () -> Unit = {
        assertTrue(RpmVersions.compare(a, b) > 0, "$a should be newer than $b")
        assertTrue(RpmVersions.compare(b, a) < 0, "$b should be older than $a")
    }

    @Test
    fun `orders epochs, versions and releases as rpm does`() {
        assertAll(
            newer("1.2.11-40.el9", "1.2.11-39.el9"),
            newer("1.2.11-40.el9_8", "1.2.11-40.el9"),
            newer("1.10-1", "1.9-1"),
            newer("1:1.0-1", "2.0-1"),
            newer("1.0-1", "1.0~rc1-1"),
            newer("1.0a-1", "1.0-1"),
            newer("2.34-283.el9_8", "2.34-125.el9_5.8"),
            { assertTrue(RpmVersions.compare("1.0-01", "1.0-1") == 0) },
        )
    }
}
