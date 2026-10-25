package io.github.fiftieshousewife.javaapp

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class JdkVersionFormsTest {
    @ParameterizedTest(name = "{0} refuses {1}")
    @CsvSource(
        "TEMURIN, 21.0.13",
        "ORACLE, 25.0.3+9",
        "CORRETTO, 21.0.13",
        "ZULU, 21.0.13",
        "LIBERICA, 21.0.13",
        "MICROSOFT, 21.0.12+7",
        "SAPMACHINE, 21.0.13+9",
    )
    fun `each distribution refuses a version its layout cannot address`(
        distribution: JdkDistribution,
        version: String,
    ) {
        assertThrows(GradleException::class.java) { distribution.requireVersion(JdkVersion(version)) }
    }

    @ParameterizedTest(name = "{0} accepts its own example")
    @EnumSource(JdkDistribution::class)
    fun `each distribution accepts the example it quotes in its errors`(distribution: JdkDistribution) {
        assertDoesNotThrow { distribution.requireVersion(JdkVersion(distribution.versionForm.example)) }
    }

    @Test
    fun `every catalogue pin is in its distribution's form and for its own feature release`() {
        val pins =
            JdkDistribution.entries.flatMap { distribution ->
                JdkCatalog.SUPPORTED_FEATURES.mapNotNull { feature ->
                    JdkCatalog.cataloguedJdk(distribution, feature)?.let {
                        Triple(distribution, feature, JdkVersion(it))
                    }
                }
            }
        assertAll(
            pins.map { (distribution, feature, version) ->
                Executable {
                    distribution.requireVersion(version)
                    assertEquals(feature, version.feature, "jdk.${distribution.id}.$feature=$version")
                }
            },
        )
    }

    @Test
    fun `reads the feature release from any vendor's version form`() {
        assertAll(
            { assertEquals(21, JdkVersion("21.0.13+9").feature) },
            { assertEquals(25, JdkVersion("25.0.5.0.9").feature) },
            { assertEquals(21, JdkVersion("21.54.107-ca-jdk21.0.13").feature) },
            { assertEquals("21.0.12.1_1", JdkVersion("21.0.12.1+1").underscored) },
        )
    }
}
