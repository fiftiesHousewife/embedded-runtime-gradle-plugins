package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.net.URI

class TargetTokensTest {
    private val everyToken = "[target] [classifier] [os] [arch] [rpmArch] [containerArch]"

    @Test
    fun `every target token, for every supported target`() {
        val expected =
            mapOf(
                Target.LINUX_X64 to "linux-x64 linux-x64 linux x64 x86_64 amd64",
                Target.LINUX_AARCH64 to "linux-aarch64 linux-aarch64 linux aarch64 aarch64 arm64",
                Target.WINDOWS_X64 to "windows-x64 windows-x64 windows x64 x86_64 amd64",
                Target.MAC_X64 to "mac-x64 mac-x64 mac x64 x86_64 amd64",
                Target.MAC_AARCH64 to "mac-aarch64 mac-aarch64 mac aarch64 aarch64 arm64",
            )
        assertEquals(Target.SUPPORTED.toSet(), expected.keys)
        assertAll(
            expected.map { (target, rendered) ->
                Executable { assertEquals(rendered, TargetTokens.render(everyToken, target), target.id) }
            },
        )
    }

    @Test
    fun `Ivy's own tokens are filled from the artifact`() {
        val rendered = TargetTokens.render(
            "[organisation]/[module]-[revision]-[os].[ext]",
            Target.LINUX_X64,
            mapOf("organisation" to "oracle", "module" to "jdk", "revision" to "21_5", "ext" to "rpm"),
        )
        assertEquals("oracle/jdk-21_5-linux.rpm", rendered)
    }

    @Test
    fun `the stretch from the first target token to the last becomes Ivy's classifier`() {
        assertAll(
            {
                assertEquals(
                    "java/jdk[revision]-64_[classifier].rpm" to "[os].[rpmArch]",
                    TargetTokens.split("java/jdk[revision]-64_[os].[rpmArch].rpm"),
                )
            },
            {
                assertEquals(
                    "[module]-[classifier].[ext]" to "[classifier]",
                    TargetTokens.split("[module]-[classifier].[ext]"),
                )
            },
            {
                assertEquals(
                    "[organisation]/[classifier].rpm" to "[os]/jdk-[revision]_[arch]",
                    TargetTokens.split("[organisation]/[os]/jdk-[revision]_[arch].rpm"),
                )
            },
        )
    }

    @Test
    fun `a pattern without target tokens is used as it is`() {
        val (pattern, stretch) = TargetTokens.split("[organisation]/[module]/[revision]/[artifact].[ext]")
        assertAll(
            { assertEquals("[organisation]/[module]/[revision]/[artifact].[ext]", pattern) },
            { assertNull(stretch) },
        )
    }

    @Test
    fun `target tokens may sit inside an optional part, but not reach across one`() {
        assertAll(
            { assertEquals("[module](-[classifier]).[ext]" to "[os]", TargetTokens.split("[module](-[os]).[ext]")) },
            { assertThrows(GradleException::class.java) { TargetTokens.split("[os](-[module])-[arch].[ext]") } },
            { assertThrows(GradleException::class.java) { TargetTokens.split("([os]-)[arch].[ext]") } },
        )
    }

    private fun uri(pattern: String, revision: String, target: Target): String {
        val repository = RuntimeRepository.curated("jdks", URI("$REPO/"), pattern, "oracle")
        return repository.downloadUri(repository.curated("jdk", revision, target)).toString()
    }

    /** The names RPM files really have, from one pattern per tree, for every CPU. */
    @Test
    fun `RPM names, vendor and corporate, come out right for each CPU`() {
        val corporate = "java/jdk[revision]-64_[os].[rpmArch].rpm"
        val oracle = "jdk-[revision]_[os]-[arch]_bin.rpm"
        val nested = "[organisation]/[os]/[rpmArch]/jdk-[revision].rpm"
        val expected =
            listOf(
                uri(corporate, "21_5", Target.LINUX_X64) to "java/jdk21_5-64_linux.x86_64.rpm",
                uri(corporate, "21_5", Target.LINUX_AARCH64) to "java/jdk21_5-64_linux.aarch64.rpm",
                uri(oracle, "21.0.5", Target.LINUX_X64) to "jdk-21.0.5_linux-x64_bin.rpm",
                uri(oracle, "21.0.5", Target.LINUX_AARCH64) to "jdk-21.0.5_linux-aarch64_bin.rpm",
                uri(nested, "21.0.5", Target.LINUX_AARCH64) to "oracle/linux/aarch64/jdk-21.0.5.rpm",
            )
        assertAll(expected.map { (actual, path) -> Executable { assertEquals("$REPO/$path", actual) } })
    }

    @Test
    fun `version tokens can sit in the pattern itself, beside the target's`() {
        val repository =
            RuntimeRepository.curated(
                "jdks",
                URI("$REPO/"),
                "java/jdk[feature]_[update]-64_[os].[rpmArch].rpm",
                "oracle",
            )
        val artifact = repository.curated(
            "jdk",
            "21.0.5",
            Target.LINUX_AARCH64,
            mapOf(
                "feature" to "21",
                "update" to "5",
            ),
        )
        assertEquals("$REPO/java/jdk21_5-64_linux.aarch64.rpm", repository.downloadUri(artifact).toString())
    }

    @Test
    fun `a token with no value fails, naming the tokens there are`() {
        val repository = RuntimeRepository.curated("jdks", URI("$REPO/"), "jdk-[revision]-[plat].zip", "oracle")
        val failure =
            assertThrows(GradleException::class.java) { repository.curated("jdk", "21.0.5", Target.MAC_AARCH64) }
        assertAll(
            {
                assertTrue(
                    failure.message!!.startsWith("No value for [plat] in '[plat]' for mac-aarch64."),
                    failure.message,
                )
            },
            { assertTrue(failure.message!!.contains("[rpmArch]"), failure.message) },
        )
    }

    private companion object {
        const val REPO = "https://repo.example.com/yum-local"
    }
}
