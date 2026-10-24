package io.github.fiftieshousewife.platform.runtime

import io.github.fiftieshousewife.platform.PlatformProperties
import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.util.Properties

/**
 * The approved runtime versions, shipped inside the plugin jar.
 *
 * Releasing a new plugin version is how a runtime bump (for example a quarterly Java CPU) is rolled
 * out to every repository. A machine or project can override a pin with
 * `platform.runtime.jdk.<feature>` or `platform.runtime.node` for an emergency patch before the next
 * plugin release.
 */
object RuntimeCatalog {
    private const val RESOURCE = "/io/github/fiftieshousewife/platform/runtimes.properties"

    /**
     * Java feature releases a bundle may target.
     *
     * The floor is 11: a bundle's runtime is built with `jlink`, which needs a modular JDK, and 11
     * is the oldest modular release Temurin still ships. Java 8 is therefore out of scope — it has
     * no modules to link, so supporting it would mean a second packaging path that copies a whole
     * JRE and ignores `bundle.jlinkModules`.
     *
     * The ceiling moves with the newest release Gradle can compile and test with; raising it means
     * bumping Gradle, adding a `jdk.<feature>` pin, and checking the `--compress` option below.
     *
     * None of this is the version Gradle itself runs on: Gradle needs Java 17 or newer, whatever a
     * bundle targets.
     */
    val SUPPORTED_FEATURES = MIN_JAVA_FEATURE..MAX_JAVA_FEATURE
    private const val MIN_JAVA_FEATURE = 11
    private const val MAX_JAVA_FEATURE = 25

    /** `--compress` took named levels in Java 21 and deprecated the numeric ones. */
    const val NAMED_COMPRESSION_FROM = 21

    private val pins: Properties by lazy {
        Properties().apply {
            RuntimeCatalog::class.java.getResourceAsStream(RESOURCE)?.use(::load)
                ?: error("Missing $RESOURCE in plugin jar")
        }
    }

    val defaultJavaFeature: Int
        get() = pins.getProperty("jdk.default").toInt()

    /**
     * The pin for one distribution's feature release. Keys are `jdk.<distribution>.<feature>`,
     * falling back to `jdk.<feature>` so a single-distribution catalogue stays terse.
     */
    fun cataloguedJdk(
        distribution: JdkDistribution,
        feature: Int,
    ): String? = pins.getProperty("jdk.${distribution.id}.$feature") ?: pins.getProperty("jdk.$feature")

    val cataloguedNode: String
        get() = pins.getProperty("node.default")

    /**
     * The exact JDK for [feature]: a `platform.runtime.jdk.<feature>` override, else the catalogue
     * pin, else null. Shared by the bundle and the toolchain resolver so they cannot disagree.
     */
    fun pinnedJdk(
        providers: ProviderFactory,
        distribution: JdkDistribution,
        feature: Int,
    ): JdkVersion? =
        (providers.gradleProperty(PlatformProperties.RUNTIME_JDK_PREFIX + feature).orNull ?: cataloguedJdk(distribution, feature))
            ?.let(::JdkVersion)
            ?.also(distribution::requireVersion)

    fun jdkVersion(
        providers: ProviderFactory,
        distribution: JdkDistribution,
        feature: Provider<Int>,
    ): Provider<String> =
        feature.map { f ->
            val supported = requireSupported(f)
            (pinnedJdk(providers, distribution, supported) ?: throw unknownJdk(distribution, supported)).value
        }

    fun nodeVersion(providers: ProviderFactory): Provider<String> =
        providers.gradleProperty(PlatformProperties.RUNTIME_NODE).orElse(cataloguedNode)

    fun requireSupported(feature: Int): Int {
        if (feature !in SUPPORTED_FEATURES) {
            throw GradleException(
                "bundle.javaVersion is $feature; this plugin supports Java " +
                    "${SUPPORTED_FEATURES.first} to ${SUPPORTED_FEATURES.last}. Bundled runtimes are " +
                    "built with jlink, which needs a modular JDK.",
            )
        }
        return feature
    }

    fun unknownJdk(
        distribution: JdkDistribution,
        feature: Int,
    ) = GradleException(
        "No ${distribution.id} JDK $feature pin in the platform catalogue. Set " +
            "${PlatformProperties.RUNTIME_JDK_PREFIX}$feature to an exact version, e.g. " +
            if (distribution == JdkDistribution.ORACLE) "$feature.0.2." else "$feature.0.1+8.",
    )
}

/**
 * An exact JDK version.
 *
 * Distributions disagree about the build number: Temurin's `21.0.13+9` carries one and needs it in
 * the download URL, Oracle's `25.0.2` has none. Both parse here, and
 * [JdkDistribution.requireVersion] rejects the form its own layout cannot address.
 */
data class JdkVersion(
    val value: String,
) {
    init {
        require(FORMAT.matches(value)) {
            "JDK version '$value' must be an exact version, e.g. 21.0.13+9 (Temurin) or 25.0.2 (Oracle)"
        }
    }

    val feature: Int get() = value.substringBefore('.').substringBefore('+').toInt()

    /** The build number, or null for a distribution that does not publish one. */
    val build: String? get() = value.substringAfter('+', "").takeIf(String::isNotEmpty)

    /** `21.0.13_9` — the form Adoptium uses in file names and the curated repository uses in paths. */
    val underscored: String get() = value.replace('+', '_')

    fun withoutBuild(): String = value.substringBefore('+')

    override fun toString() = value

    private companion object {
        val FORMAT = Regex("""\d+(\.\d+)*(\+\d+)?""")
    }
}
