package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.BinaryRepository
import io.github.fiftieshousewife.appcore.EmbeddedRuntimeSettings
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Each setter in `embeddedRuntime {}` stands for exactly the Gradle property the plugins read. */
class EmbeddedRuntimeSettingsTest {
    private val settings = ProjectBuilder.builder().build().objects.newInstance(EmbeddedRuntimeSettings::class.java)

    @Test
    fun `every JDK setter is named for its Gradle property`() {
        settings.jdk.apply {
            distribution.set(JdkSources.DISTRIBUTION)
            url.set(JdkSources.PUBLIC_URL)
            pattern.set(JdkSources.PUBLIC_PATTERN)
            localPath.set(JdkSources.LOCAL_PATH)
            localPattern.set(JdkSources.LOCAL_PATTERN)
            binaryRepo.set(JdkSources.BINARY_REPO_NAME)
            binaryRepoPattern.set(JdkSources.BINARY_REPO_PATTERN)
            revisionFormat.set(JdkSources.REVISION_FORMAT)
            yumRepo.set(JdkSources.YUM_REPO)
            yumPackage.set(JdkSources.YUM_PACKAGE)
        }
        settings.asProperties().forEach { (name, value) -> assertEquals(name, value) }
    }

    @Test
    fun `pins and paths become the properties they stand for`() {
        settings.jdk.pin(21, "21.0.5")
        settings.jdk.path(21, "windows-x64", "C:/Program Files/Java/jdk-21")
        assertEquals(
            mapOf(
                JdkSources.VERSION_PREFIX + "21" to "21.0.5",
                JdkSources.pathProperty(21, io.github.fiftieshousewife.appcore.Target.WINDOWS_X64) to
                    "C:/Program Files/Java/jdk-21",
            ),
            settings.asProperties(),
        )
    }

    @Test
    fun `the binary repository setters are named for theirs, and unset ones are left to the properties`() {
        settings.binaryRepository.repository.set("artifactory")
        settings.binaryRepository.allowInsecureProtocol.set(true)
        assertEquals(
            mapOf(BinaryRepository.REPOSITORY to "artifactory", BinaryRepository.ALLOW_INSECURE to "true"),
            settings.asProperties(),
        )
    }
}
