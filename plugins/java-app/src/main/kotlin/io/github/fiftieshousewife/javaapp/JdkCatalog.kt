package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.util.Properties

/** Shipped in the jar, so a plugin release rolls a JDK bump out everywhere; `javaApp.jdk.<feature>` overrides one. */
object JdkCatalog {
    private const val RESOURCE = "/io/github/fiftieshousewife/javaapp/jdks.properties"

    /**
     * 11 is the oldest modular release, and jlink needs modules. The ceiling is the newest Gradle can compile with;
     * raising it means bumping Gradle, adding a pin and checking [NAMED_COMPRESSION_FROM].
     */
    val SUPPORTED_FEATURES = MIN_JAVA_FEATURE..MAX_JAVA_FEATURE
    private const val MIN_JAVA_FEATURE = 11
    private const val MAX_JAVA_FEATURE = 27

    /** `--compress` took named levels in Java 21 and deprecated the numeric ones. */
    const val NAMED_COMPRESSION_FROM = 21

    private val pins: Properties by lazy {
        Properties().apply {
            JdkCatalog::class.java.getResourceAsStream(RESOURCE)?.use(::load)
                ?: error("Missing $RESOURCE in plugin jar")
        }
    }

    val defaultJavaFeature: Int
        get() = pins.getProperty("jdk.default").toInt()

    /** Falls back to `jdk.<feature>` so a single-distribution catalogue stays terse. */
    fun cataloguedJdk(distribution: JdkDistribution, feature: Int): String? =
        pins.getProperty("jdk.${distribution.id}.$feature") ?: pins.getProperty("jdk.$feature")

    /** Shared by the archive and the toolchain resolver so they cannot disagree. */
    fun pinnedJdk(providers: ProviderFactory, distribution: JdkDistribution, feature: Int): JdkVersion? =
        (providers.setting(JdkSources.VERSION_PREFIX + feature) ?: cataloguedJdk(distribution, feature))
            ?.let(::JdkVersion)
            ?.also(distribution::requireVersion)

    fun jdkVersion(
        providers: ProviderFactory,
        distribution: JdkDistribution,
        feature: Provider<Int>,
    ): Provider<String> = feature.map { f ->
        val supported = requireSupported(f)
        (pinnedJdk(providers, distribution, supported) ?: throw unknownJdk(distribution, supported)).value
    }

    fun requireSupported(feature: Int): Int {
        if (feature !in SUPPORTED_FEATURES) {
            throw GradleException(
                "javaApp.javaVersion is $feature; this plugin supports Java " +
                    "${SUPPORTED_FEATURES.first} to ${SUPPORTED_FEATURES.last}. App runtimes are " +
                    "built with jlink, which needs a modular JDK.",
            )
        }
        return feature
    }

    fun unknownJdk(distribution: JdkDistribution, feature: Int) = GradleException(
        "No ${distribution.id} JDK $feature pin in the java-app catalogue. Set " +
            "${JdkSources.VERSION_PREFIX}$feature to an exact ${distribution.id} version, " +
            "in the form ${distribution.versionForm.example}. ${distribution.coverage}".trimEnd(),
    )
}

/** An exact version in its vendor's form, starting with the feature release ([JdkDistribution.requireVersion]). */
data class JdkVersion(val value: String) {
    init {
        require(FORMAT.matches(value)) {
            "JDK version '$value' must be an exact version that starts with the feature release, e.g. 21.0.13+9"
        }
    }

    val feature: Int get() = value.takeWhile(Char::isDigit).toInt()

    /** `21.0.13_9` — the form Adoptium uses in file names and the curated repository uses in paths. */
    val underscored: String get() = value.replace('+', '_')

    /** Fills [format]'s `[version]`, `[underscored]`, `[feature]`, `[interim]`, `[update]`, `[patch]`, `[build]`. */
    fun format(format: String): String = TOKEN.replace(format) { tokens[it.groupValues[1]] ?: it.value }

    val tokens: Map<String, String>
        get() {
            val numbers = value.substringBefore('+').substringBefore('-').split('.')
            return mapOf(
                "version" to value,
                "underscored" to underscored,
                "feature" to feature.toString(),
                "interim" to numbers.getOrElse(1) { "0" },
                "update" to numbers.getOrElse(2) { "0" },
                "patch" to numbers.getOrElse(3) { "0" },
                "build" to value.substringAfter('+', ""),
            )
        }

    override fun toString() = value

    private companion object {
        // The version ends up in a URL path, so only characters some vendor actually uses get in.
        val FORMAT = Regex("""\d+[0-9A-Za-z.+-]*""")
        val TOKEN = Regex("""\[([a-z]+)]""")
    }
}
