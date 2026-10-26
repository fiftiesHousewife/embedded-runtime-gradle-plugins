package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RuntimePolicyTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a comment after a value is refused, not read as part of it`() {
        dir.resolve(RuntimePolicy.FILE).writeText(
            "id=acme\nversion=1\n# on its own line: fine\n" +
                "lockProperties=true      # security.overridePropertiesFile=false\n",
        )
        val failure = assertThrows(GradleException::class.java) { RuntimePolicy.load(dir) }
        assertTrue(failure.message!!.contains("lockProperties has a comment after the value"), failure.message)
    }
}
