package io.github.fiftieshousewife.appcore

import java.util.Properties

/**
 * Facts about this release, written into a resource at build time from the build and its version
 * catalogue. `app-core`, `java-app` and `node-app` are always released together, so one version
 * describes all three.
 */
object PluginVersion {
    private val properties: Properties by lazy {
        Properties().apply {
            PluginVersion::class.java
                .getResourceAsStream("/io/github/fiftieshousewife/appcore/version.properties")
                ?.use(::load)
                ?: error("version.properties missing from the app-core jar")
        }
    }

    val current: String get() = properties.getProperty("version")

    /** The generated Dockerfile's default base image (`name:tag`), pinned in the version catalogue. */
    val dockerBaseImage: String get() = properties.getProperty("dockerBaseImage")

    /** The digest [dockerBaseImage] is pinned to: its multi-arch index, so it holds for every CPU. */
    val dockerBaseDigest: String get() = properties.getProperty("dockerBaseDigest")

    /** sigstore-java, which `app-smoke-test` resolves to verify Sigstore bundles. */
    val sigstoreJava: String get() = properties.getProperty("sigstoreJava")

    /** The Bouncy Castle release on the plugins' classpath, which the Sigstore verifier is aligned with. */
    val bouncycastle: String get() = properties.getProperty("bouncycastle")
}
