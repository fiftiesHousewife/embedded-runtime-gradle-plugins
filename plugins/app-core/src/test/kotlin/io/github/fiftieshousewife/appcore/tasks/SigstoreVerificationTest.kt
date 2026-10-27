package io.github.fiftieshousewife.appcore.tasks

import dev.sigstore.KeylessVerifier
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A real signed release: sigstore-java 2.3.0's own jar, as on this test's classpath, with the bundle
 * its release workflow published to Maven Central. Verified offline against a copy of the
 * public-good trusted root.
 */
class SigstoreVerificationTest {
    @TempDir
    lateinit var tmp: File

    private val jar = File(KeylessVerifier::class.java.protectionDomain.codeSource.location.toURI())
    private val bundle = resource("sigstore-java-2.3.0.jar.sigstore.json")
    private val trustedRoot = resource("trusted_root.json")
    private val releaseWorkflow =
        SignerIdentity(
            "^https://github\\.com/sigstore/sigstore-java/\\.github/workflows/" +
                "release-sigstore-java-from-tag\\.yaml@refs/tags/v2\\.3\\.0$",
            "https://token.actions.githubusercontent.com",
        )

    @Test
    fun `an archive signed by the expected workflow verifies`() {
        assertTrue(jar.name == "sigstore-java-2.3.0.jar", jar.name)
        assertDoesNotThrow { SigstoreVerification.verify(jar, bundle, releaseWorkflow, trustedRoot) }
    }

    @Test
    fun `a signature by anyone else is refused`() {
        val someoneElse = releaseWorkflow.copy(identity = """^https://github\.com/example/orders/.*$""")
        val failure = assertThrows(GradleException::class.java) {
            SigstoreVerification.verify(jar, bundle, someoneElse, trustedRoot)
        }
        assertTrue(
            failure.message!!.contains("is not signed by ^https://github\\.com/example/orders/"),
            failure.message,
        )
    }

    @Test
    fun `a changed archive is refused`() {
        val changed = jar.copyTo(tmp.resolve(jar.name)).apply { appendBytes(byteArrayOf(0)) }
        assertThrows(GradleException::class.java) {
            SigstoreVerification.verify(changed, bundle, releaseWorkflow, trustedRoot)
        }
    }

    private fun resource(name: String) =
        File(SigstoreVerificationTest::class.java.getResource("/sigstore/$name")!!.toURI())
}
