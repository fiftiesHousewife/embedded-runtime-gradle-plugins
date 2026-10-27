package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource

/**
 * Every vendor's real download URL, verified by hand against its site: the one thing no functional
 * test can check without the internet, and the thing a refactor is most likely to break.
 */
class JdkDownloadUrlsTest {
    @ParameterizedTest(name = "{0} {1} for {2}")
    @CsvFileSource(resources = ["/jdk-download-urls.csv"], numLinesToSkip = 1)
    fun `builds each vendor's own download URL`(
        distribution: JdkDistribution,
        version: String,
        target: String,
        url: String,
    ) {
        val sources = JdkSources(distribution, JdkLocation.Public())
        assertEquals(
            url,
            sources.downloadUri(JdkVersion(version), Target.parse(target.lowercase().replace('_', '-'))).toString(),
        )
    }
}
