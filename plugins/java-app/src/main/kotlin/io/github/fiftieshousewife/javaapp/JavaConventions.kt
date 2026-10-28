package io.github.fiftieshousewife.javaapp

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File

/** The defaults behind `javaApp {}`, the values derived from it, and the compile toolchain they imply. */
internal class JavaConventions(
    private val project: Project,
    private val extension: JavaAppExtension,
    detectedModules: Provider<List<String>>,
) {
    val sources = JdkSources.from(project.providers)

    /**
     * Every use goes through this, so an unsupported release is reported once, by name, rather than
     * as a missing pin or a jlink failure further down.
     */
    private val javaFeature = extension.javaVersion.map(JdkCatalog::requireSupported)

    /** The JDK linked into the archive: `javaApp.jdk.<feature>`, else the catalogue pin. */
    val jdkVersion: Provider<String> = JdkCatalog.jdkVersion(project.providers, sources.distribution, javaFeature)

    /** What the app needs: jdeps' modules and [JavaAppExtension.additionalModules]. */
    val appModules: Provider<List<String>> =
        detectedModules.zip(extension.additionalModules) { detected, extra -> (detected + extra).distinct() }

    val jlinkOptions: Provider<List<String>> = javaFeature.map(::jlinkOptions)

    fun apply() {
        extension.javaVersion.convention(JdkCatalog.defaultJavaFeature)
        extension.additionalModules.convention(emptyList())
        extension.moduleSets.convention(javaFeature.map(ModuleSets::defaults))
        extension.jvmArgs.convention(emptyList())
        extension.runtimeDefaults.convention(true)
        val overlay = project.layout.projectDirectory.dir("src/deploy/runtime").asFile
        extension.runtimeOverlay.convention(project.layout.dir(project.provider { overlay.takeIf(File::isDirectory) }))
        security()
        toolchain()
    }

    private fun security() = extension.security.apply {
        replaceDefaultTrust.convention(false)
        trustedCertificateUris.convention(emptyMap())
        properties.convention(emptyMap())
        disabledAlgorithms.convention(emptyMap())
        lockProperties.convention(false)
        forbiddenModules.convention(listOf("jdk.jdwp.agent"))
    }

    private fun toolchain() = project.extensions.getByType(JavaPluginExtension::class.java).toolchain {
        languageVersion.convention(javaFeature.map(JavaLanguageVersion::of))
        vendor.convention(sources.distribution.vendorSpec)
    }

    companion object {
        /** Strip what a server never needs; `--compress` took named levels in Java 21. */
        fun jlinkOptions(feature: Int) = listOf("--strip-debug", "--no-man-pages", "--no-header-files") +
            if (feature >= JdkCatalog.NAMED_COMPRESSION_FROM) "--compress=zip-6" else "--compress=2"
    }
}
