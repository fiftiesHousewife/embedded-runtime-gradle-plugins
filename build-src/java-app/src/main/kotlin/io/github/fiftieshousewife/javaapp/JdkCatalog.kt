package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.setting
import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.util.Properties

/**
 * The approved JDK versions, shipped inside the plugin jar (written from `gradle/libs.versions.toml`).
 *
 * Releasing a new plugin version is how a JDK bump (for example a quarterly Java CPU) is rolled out
 * to every repository. A machine or project can override a pin with `javaApp.jdk.<feature>` for an
 * emergency patch before the next plugin release.
 */
object JdkCatalog {
    private const val RESOURCE = "/io/github/fiftieshousewife/javaapp/jdks.properties"

    /**
     * Java feature releases an app may target.
     *
     * The floor is 11: an app's runtime is built with `jlink`, which needs a modular JDK, and 11
     * is the oldest modular release Temurin still ships. Java 8 is therefore out of scope — it has
     * no modules to link, so supporting it would mean a second packaging path that copies a whole
     * JRE and ignores `javaApp.jlinkModules`.
     *
     * The ceiling moves with the newest release Gradle can compile and test with; raising it means
     * bumping Gradle, adding a `jdk.<feature>` pin, and checking the `--compress` option below.
     *
     * None of this is the version Gradle itself runs on: Gradle needs Java 17 or newer, whatever an
     * app targets.
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

    /**
     * The pin for one distribution's feature release. Keys are `jdk.<distribution>.<feature>`,
     * falling back to `jdk.<feature>` so a single-distribution catalogue stays terse.
     */
    fun cataloguedJdk(distribution: JdkDistribution, feature: Int): String? =
        pins.getProperty("jdk.${distribution.id}.$feature") ?: pins.getProperty("jdk.$feature")

    /**
     * The exact JDK for [feature]: a `javaApp.jdk.<feature>` override, else the catalogue pin, else
     * null. Shared by the archive and the toolchain resolver so they cannot disagree.
     */
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

/**
 * An exact JDK version, in whatever form its vendor publishes: Temurin's `21.0.13+9`, Oracle's
 * `25.0.3`, Corretto's `21.0.13.0.10`, Zulu's `21.54.107-ca-jdk21.0.13`. Every form starts with
 * the feature release; [JdkDistribution.requireVersion] checks the rest against its own layout.
 */
data class JdkVersion(val value: String) {
    init {
        require(FORMAT.matches(value)) {
            "JDK version '$value' must be an exact version that starts with the feature release, e.g. 21.0.13+9"
        }
    }

    val feature: Int get() = value.takeWhile(Char::isDigit).toInt()

    /** `21.0.13_9` — the form Adoptium uses in file names and the curated repository uses in paths. */
    val underscored: String get() = value.replace('+', '_')

    /**
     * [format] with its tokens filled from this version, for naming files in a repository laid out
     * your own way: `[version]` (`21.0.5+9`), `[underscored]` (`21.0.5_9`), `[feature]` (`21`),
     * `[interim]` (`0`), `[update]` (`5`), `[patch]` (`0` when absent) and `[build]` (`9`, empty when absent).
     */
    fun format(format: String): String = TOKEN.replace(format) { tokens[it.groupValues[1]] ?: it.value }

    /** The version's tokens by name, for [format] and for file patterns, which may use them directly. */
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
