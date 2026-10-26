package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NodeAppSettingsPluginTest {
    @TempDir
    lateinit var tmp: File

    private fun repositories(properties: String): List<String> {
        val project =
            TestProject(tmp).apply {
                file("gradle.properties", properties)
                repositoryProbe(NodeAppSettingsPlugin.ID)
            }
        return project.repositories(project.build("help").output)
    }

    @Test
    fun `without a binary repository Node comes from nodejs-org and no JDK repository is added`() {
        val repos = repositories("")
        assertAll(
            { assertTrue(repos.contains("REPO MavenRepo https://repo.maven.apache.org/maven2/"), repos.toString()) },
            { assertTrue(repos.contains("REPO nodejsDist https://nodejs.org/dist/"), repos.toString()) },
            { assertFalse(repos.any { it.contains("Jdks") }, repos.toString()) },
        )
    }

    @Test
    fun `a binary repository serves Node from the curated runtimes repository`() {
        val repos = repositories("binaryRepo.url=https://repo.example.com")
        assertAll(
            {
                assertTrue(
                    repos.contains("REPO binaryRepoNode https://repo.example.com/runtimes/"),
                    repos.toString(),
                )
            },
            { assertFalse(repos.any { it.contains("nodejs.org") }, repos.toString()) },
        )
    }
}
