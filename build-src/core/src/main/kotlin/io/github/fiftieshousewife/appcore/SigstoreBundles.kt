package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

/**
 * A bundle is not part of the Gradle Module Metadata (`dev.sigstore.sign` uploads it beside each file, as the
 * signing plugin does `.asc` files), so it is asked for by its Maven coordinates.
 */
internal object SigstoreBundles {
    /** The OIDC issuer of GitHub Actions' identity tokens, the default `signerIssuer`. */
    const val GITHUB_ACTIONS = "https://token.actions.githubusercontent.com"

    fun verify(project: Project, archives: NamedDomainObjectProvider<ResolvableConfiguration>, task: AppSmokeTest) {
        task.signatures.from(of(project, archives).get().incoming.artifactView { lenient(true) }.files)
        task.verifierClasspath.from(verifierClasspath(project))
    }

    /**
     * `orders-1.4.2-linux-x64.tar.gz` of `com.example:orders:1.4.2` has its bundle at
     * `com.example:orders:1.4.2:linux-x64@tar.gz.sigstore.json`; null for a file not named that way.
     */
    fun notation(id: ModuleComponentIdentifier, fileName: String): String? {
        val prefix = "${id.module}-${id.version}-"
        if (!fileName.startsWith(prefix)) return null
        val rest = fileName.removePrefix(prefix)
        val extension = rest.substringAfter('.', "").ifEmpty { return null }
        return "${id.group}:${id.module}:${id.version}:${rest.substringBefore(
            '.',
        )}@$extension${AppSmokeTest.BUNDLE_SUFFIX}"
    }

    fun of(
        project: Project,
        archives: NamedDomainObjectProvider<ResolvableConfiguration>,
    ): NamedDomainObjectProvider<ResolvableConfiguration> {
        val declared = project.configurations.dependencyScope("smokeTestSignaturesDependencies") {
            dependencies.addAllLater(
                archives.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { results ->
                    results.mapNotNull { result ->
                        (result.id.componentIdentifier as? ModuleComponentIdentifier)
                            ?.let { notation(it, result.file.name) }
                            ?.let(project.dependencies::create)
                    }
                },
            )
        }
        // The same variant as the archive's, whose artifact the dependency then names explicitly.
        return MachineArchives.resolvable(project, "smokeTestSignatures", declared).also {
            it.configure { description = "The Sigstore bundle published beside each smokeTest archive" }
        }
    }

    /** Its Bouncy Castle is aligned with the plugins' own, since the verifying worker sees both. */
    private fun verifierClasspath(project: Project): NamedDomainObjectProvider<ResolvableConfiguration> {
        val declared = project.configurations.dependencyScope("sigstoreVerifierDependencies") {
            dependencies.add(project.dependencies.create("dev.sigstore:sigstore-java:${PluginVersion.sigstoreJava}"))
            listOf("bcprov", "bcpkix", "bcutil").forEach { module ->
                dependencyConstraints.add(
                    project.dependencies.constraints.create(
                        "org.bouncycastle:$module-jdk18on:${PluginVersion.bouncycastle}",
                    ),
                )
            }
        }
        return project.configurations.resolvable("sigstoreVerifier") {
            extendsFrom(declared.get())
            description = "sigstore-java, which verifies the smokeTest archives' bundles"
            // A plain Java runtime classpath, so Guava resolves to its JRE flavour, not Android's.
            val objects = project.objects
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
                attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
                attribute(
                    TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
                )
            }
        }
    }
}
