package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.EmbeddedRuntimeSettings
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Each Node setter in `embeddedRuntime {}` stands for exactly the Gradle property the plugin reads. */
class NodeSettingsTest {
    @Test
    fun `every Node setter is named for its Gradle property`() {
        val settings = ProjectBuilder.builder().build().objects.newInstance(EmbeddedRuntimeSettings::class.java)
        settings.node.apply {
            version.set(NodeSources.VERSION)
            source.set(NodeSources.SOURCE)
            pattern.set(NodeSources.PATTERN)
            npmRegistry.set(NodeSources.NPM_REGISTRY)
            npmCaFile.set(NodeSources.NPM_CA_FILE)
        }
        settings.binaryRepository.npmRepo.set(NodeSources.BINARY_REPO_NPM)
        val properties = settings.asProperties()
        assertEquals(6, properties.size)
        properties.forEach { (name, value) -> assertEquals(name, value) }
    }
}
