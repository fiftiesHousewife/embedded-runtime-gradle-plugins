package io.github.fiftieshousewife.appcore

import org.gradle.api.Action
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Nested

/**
 * `embeddedRuntime { … }` in `settings.gradle.kts`: every setting the settings plugins read, set in
 * code. Each stands for the Gradle property named beside it, and a value set here wins over that
 * property; anything left unset still comes from the properties, so machine-wide settings in
 * `~/.gradle/gradle.properties` keep working.
 *
 * ```kotlin
 * embeddedRuntime {
 *     binaryRepository { repository = "artifactory" }
 *     jdk {
 *         distribution = "oracle"
 *         pin(21, "21.0.5")
 *         source = "yum:yum-local"
 *         path(21, "windows-x64", "C:/Program Files/Java/jdk-21")
 *     }
 * }
 * ```
 */
abstract class EmbeddedRuntimeSettings {
    @get:Nested abstract val binaryRepository: BinaryRepositorySettings

    @get:Nested abstract val jdk: JdkSettings

    @get:Nested abstract val node: NodeSettings

    fun binaryRepository(action: Action<BinaryRepositorySettings>) = action.execute(binaryRepository)

    fun jdk(action: Action<JdkSettings>) = action.execute(jdk)

    fun node(action: Action<NodeSettings>) = action.execute(node)

    /** Every value set here, by the Gradle property it stands for. */
    fun asProperties(): Map<String, String> = (binaryRepository.properties() + jdk.properties() + node.properties())
        .mapNotNull { (name, value) -> value.orNull?.toString()?.let { name to it } }
        .toMap() + jdk.pins.get().mapKeys { (feature, _) -> "javaApp.jdk.$feature" } +
        jdk.paths.get()

    companion object {
        const val NAME = "embeddedRuntime"

        /** The extension, added by whichever settings plugin is applied first. */
        fun on(settings: Settings): EmbeddedRuntimeSettings =
            settings.extensions.findByType(EmbeddedRuntimeSettings::class.java)
                ?: settings.extensions.create(NAME, EmbeddedRuntimeSettings::class.java)
    }
}

/** The binary repository: the `binaryRepo.*` properties. */
abstract class BinaryRepositorySettings {
    /** `binaryRepo.repository`: the name of a repository the build declares, to borrow. */
    abstract val repository: Property<String>

    /** `binaryRepo.url`: the server, when the build declares no repository. */
    abstract val url: Property<String>
    abstract val mavenRepo: Property<String>
    abstract val gradlePluginsRepo: Property<String>
    abstract val runtimesRepo: Property<String>
    abstract val releasesRepo: Property<String>
    abstract val snapshotsRepo: Property<String>
    abstract val npmRepo: Property<String>
    abstract val allowInsecureProtocol: Property<Boolean>

    internal fun properties(): Map<String, Provider<*>> = mapOf(
        BinaryRepository.REPOSITORY to repository,
        BinaryRepository.URL to url,
        BinaryRepository.MAVEN_REPO to mavenRepo,
        BinaryRepository.GRADLE_PLUGINS_REPO to gradlePluginsRepo,
        BinaryRepository.RUNTIMES_REPO to runtimesRepo,
        BinaryRepository.RELEASES_REPO to releasesRepo,
        BinaryRepository.SNAPSHOTS_REPO to snapshotsRepo,
        "binaryRepo.repo.npm" to npmRepo,
        BinaryRepository.ALLOW_INSECURE to allowInsecureProtocol,
    )
}

/** Where JDKs come from: the `javaApp.jdk.*` properties. */
abstract class JdkSettings {
    abstract val distribution: Property<String>

    /** `javaApp.jdk.source`: an `https:` or `file:` URL, `yum:` and a repository, or a repository's name. */
    abstract val source: Property<String>

    /** `javaApp.jdk.pattern`: the files in [source], or a YUM package's name. */
    abstract val pattern: Property<String>

    /** `javaApp.jdk.reuseToolchain`: use the compile toolchain's JDK when it is exactly the pinned one. */
    abstract val reuseToolchain: Property<Boolean>

    /** `javaApp.jdk.<feature>`: the exact JDK for a feature release. */
    abstract val pins: MapProperty<Int, String>

    /** `javaApp.jdk.<feature>.<target>.path`: one JDK, a directory or an archive, for one target. */
    abstract val paths: MapProperty<String, String>

    /** `javaApp.jdk.21=21.0.5` as `pin(21, "21.0.5")`. */
    fun pin(feature: Int, version: String) = pins.put(feature, version)

    /** `javaApp.jdk.21.windows-x64.path=…` as `path(21, "windows-x64", "…")`. */
    fun path(feature: Int, target: String, path: String) =
        paths.put("javaApp.jdk.$feature.${Target.parse(target).id}.path", path)

    internal fun properties(): Map<String, Provider<*>> = mapOf(
        "javaApp.jdk.distribution" to distribution,
        "javaApp.jdk.source" to source,
        "javaApp.jdk.pattern" to pattern,
        "javaApp.jdk.reuseToolchain" to reuseToolchain,
    )
}

/** Where Node.js and npm packages come from: the `nodeApp.node.*` and `nodeApp.npm.*` properties. */
abstract class NodeSettings {
    abstract val version: Property<String>

    /** `nodeApp.node.source`: an `https:` or `file:` URL, or a repository's name. */
    abstract val source: Property<String>
    abstract val pattern: Property<String>
    abstract val npmRegistry: Property<String>
    abstract val npmCaFile: Property<String>

    internal fun properties(): Map<String, Provider<*>> = mapOf(
        "nodeApp.node.version" to version,
        "nodeApp.node.source" to source,
        "nodeApp.node.pattern" to pattern,
        "nodeApp.npm.registry" to npmRegistry,
        "nodeApp.npm.caFile" to npmCaFile,
    )
}
