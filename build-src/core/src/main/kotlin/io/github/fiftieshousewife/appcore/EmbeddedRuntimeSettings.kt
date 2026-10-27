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
 *         yumRepo = "yum-local"
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
    abstract val rpmRepo: Property<String>
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
        BinaryRepository.RPM_REPO to rpmRepo,
        "binaryRepo.repo.npm" to npmRepo,
        BinaryRepository.ALLOW_INSECURE to allowInsecureProtocol,
    )
}

/** Where JDKs come from: the `javaApp.jdk.*` properties. */
abstract class JdkSettings {
    abstract val distribution: Property<String>
    abstract val url: Property<String>
    abstract val pattern: Property<String>
    abstract val localPath: Property<String>
    abstract val localPattern: Property<String>
    abstract val binaryRepo: Property<String>
    abstract val binaryRepoPattern: Property<String>
    abstract val revisionFormat: Property<String>
    abstract val yumRepo: Property<String>
    abstract val yumPackage: Property<String>

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
        "javaApp.jdk.url" to url,
        "javaApp.jdk.pattern" to pattern,
        "javaApp.jdk.localPath" to localPath,
        "javaApp.jdk.localPattern" to localPattern,
        "javaApp.jdk.binaryRepo" to binaryRepo,
        "javaApp.jdk.binaryRepoPattern" to binaryRepoPattern,
        "javaApp.jdk.revisionFormat" to revisionFormat,
        "javaApp.jdk.yumRepo" to yumRepo,
        "javaApp.jdk.yumPackage" to yumPackage,
    )
}

/** Where Node.js and npm packages come from: the `nodeApp.node.*` and `nodeApp.npm.*` properties. */
abstract class NodeSettings {
    abstract val version: Property<String>
    abstract val url: Property<String>
    abstract val pattern: Property<String>
    abstract val binaryRepoPattern: Property<String>
    abstract val npmRegistry: Property<String>
    abstract val npmCaFile: Property<String>

    internal fun properties(): Map<String, Provider<*>> = mapOf(
        "nodeApp.node.version" to version,
        "nodeApp.node.url" to url,
        "nodeApp.node.pattern" to pattern,
        "nodeApp.node.binaryRepoPattern" to binaryRepoPattern,
        "nodeApp.npm.registry" to npmRegistry,
        "nodeApp.npm.caFile" to npmCaFile,
    )
}
